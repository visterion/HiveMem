"""Guarded write path: pre-write gates, add_cell loop, settle wait, integrity gate.

Nothing is sent to HiveMem before every gate has passed, so a stack that holds
real data is never written to. Database errors (``db.DbError``) propagate
unchanged.
"""

import hashlib
import json
import pathlib
import time
from collections.abc import Callable
from datetime import datetime, timezone

import dataset
import db
import hivemem

REALM = "longmemeval"

TOKEN_SQL = "SELECT count(*) FROM api_tokens WHERE token_hash = {hash}"
FOREIGN_SQL = "SELECT count(*) FROM cells WHERE realm IS DISTINCT FROM 'longmemeval'"
REALM_CELLS_SQL = ("SELECT topic, status, valid_until IS NULL, embedding IS NULL "
                   "FROM cells WHERE realm = 'longmemeval' ORDER BY topic")
TOPIC_SQL = "SELECT count(*) FROM cells WHERE realm = 'longmemeval' AND topic = {topic}"
UNSETTLED_SQL = ("SELECT count(*) FROM cells WHERE status = 'committed' AND valid_until IS NULL "
                 "AND length(content) > 2000 AND chunked_content_md5 IS DISTINCT FROM content_md5")
CHUNKS_SQL = "SELECT count(*), count(*) FILTER (WHERE embedding IS NULL) FROM cell_chunks"
SPAN_SQL = "SELECT min(created_at), max(created_at) FROM cells WHERE realm = 'longmemeval'"

_RESENDS = 3
_SETTLE_POLL = 5.0
_PROGRESS_EVERY = 100


class IngestError(RuntimeError):
    """A gate, the write loop or the integrity check failed."""


def token_hash(token: str) -> str:
    """SHA-256 hex digest of the token text."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def utf16_length(text: str) -> int:
    """Length of ``text`` as Java's ``String.length()`` counts it (UTF-16 units)."""
    return len(text.encode("utf-16-le")) // 2


def _count(psql: db.Psql, sql: str) -> int:
    return int(psql.scalar(sql))


def same_instance(psql: db.Psql, token: str) -> None:
    """Gate 1: the token must be known to the database behind the psql channel."""
    if _count(psql, TOKEN_SQL.format(hash=db.quote(token_hash(token)))) < 1:
        raise IngestError("token is unknown to the database behind the psql channel")


def gates(corpus: dataset.Corpus, psql: db.Psql, sidecar, token: str) -> tuple[set[str], dict]:
    """Run gates 1-4; return the topics already written and the sidecar ``/info`` reply."""
    same_instance(psql, token)
    foreign = _count(psql, FOREIGN_SQL)
    if foreign:
        raise IngestError(f"{foreign} cell(s) outside realm {REALM!r}: not a benchmark database")
    present: set[str] = set()
    for row in psql.rows(REALM_CELLS_SQL):
        topic, status, valid_until_null = row[0], row[1], row[2]
        if topic not in corpus.documents:
            raise IngestError(f"cell with topic {topic!r} in realm {REALM!r} is not in the corpus")
        if topic in present:
            raise IngestError(f"topic {topic!r} appears twice in realm {REALM!r}")
        if status != "committed" or valid_until_null != "t":
            raise IngestError(f"cell {topic!r} is not committed and current (status {status!r})")
        present.add(topic)
    info = sidecar.info()
    max_chars = info.get("max_chars")
    if not isinstance(max_chars, int) or isinstance(max_chars, bool):
        raise IngestError(f"sidecar /info has no integer max_chars: {info!r}")
    return present, info


def _topic_exists(psql: db.Psql, topic: str) -> bool:
    return _count(psql, TOPIC_SQL.format(topic=db.quote(topic))) >= 1


def _write_one(mcp, psql: db.Psql, doc: dataset.Document, sleep: Callable,
               retry_pause: float) -> bool:
    """Send one document. Return True when ``add_cell`` itself succeeded."""
    resends = 0
    while True:
        try:
            mcp.add_cell(content=doc.text, realm=REALM, topic=doc.key, signal="events",
                         valid_from=dataset.to_iso(doc.date))
            return True
        except hivemem.HiveMemError as error:
            if _topic_exists(psql, doc.key):
                return False
            if resends >= _RESENDS:
                raise IngestError(f"document {doc.key} failed after {resends} resends: {error}") from error
            resends += 1
            sleep(retry_pause)


def _settle(psql: db.Psql, timeout: float, sleep: Callable, clock: Callable, out: Callable) -> None:
    start = clock()
    while True:
        unsettled = _count(psql, UNSETTLED_SQL)
        if unsettled == 0:
            return
        if clock() - start > timeout:
            raise IngestError(f"{unsettled} cell(s) still unsettled after {timeout:.0f} s")
        out(f"settle: {unsettled} cell(s) still unsettled")
        sleep(_SETTLE_POLL)


def integrity(corpus: dataset.Corpus, psql: db.Psql, max_chars: int,
              out: Callable = lambda message: None) -> dict:
    """Check the database against the corpus; raise one ``IngestError`` naming every failure.

    Every figure is printed through ``out`` before the verdict, so it is visible on failure too.
    """
    rows = psql.rows(REALM_CELLS_SQL)
    topics = [row[0] for row in rows]
    vectorless = sorted(row[0] for row in rows if row[3] == "t")
    expected_vectorless = sorted(key for key, doc in corpus.documents.items()
                                 if utf16_length(doc.text) > max_chars)
    chunks, chunks_without = (int(v) for v in psql.rows(CHUNKS_SQL)[0])
    span = psql.rows(SPAN_SQL)[0]
    result = {
        "expected": len(corpus.documents), "cells": len(topics),
        "distinct_topics": len(set(topics)), "vectorless": vectorless,
        "expected_vectorless": expected_vectorless, "chunks": chunks,
        "chunks_without_vector": chunks_without, "unsettled": _count(psql, UNSETTLED_SQL),
        "created_at_min": span[0], "created_at_max": span[1],
    }
    for name, value in result.items():
        out(f"integrity {name}: {value}")
    failures = []
    bad_state = sorted(row[0] for row in rows if row[1] != "committed" or row[2] != "t")
    if bad_state:
        failures.append(f"cell(s) not committed and current: {bad_state}")
    if not result["cells"] == result["distinct_topics"] == result["expected"]:
        failures.append(f"cells {result['cells']} / distinct topics {result['distinct_topics']} "
                        f"/ expected {result['expected']} differ")
    if set(topics) != set(corpus.documents):
        failures.append(f"topic set differs: missing {sorted(set(corpus.documents) - set(topics))}, "
                        f"extra {sorted(set(topics) - set(corpus.documents))}")
    if vectorless != expected_vectorless:
        failures.append(f"vector mismatch: vectorless {vectorless}, expected {expected_vectorless}")
    if chunks_without:
        failures.append(f"{chunks_without} chunk(s) without vector")
    if result["unsettled"]:
        failures.append(f"{result['unsettled']} cell(s) unsettled")
    if failures:
        raise IngestError("integrity check failed: " + "; ".join(failures))
    return result


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def run_ingest(corpus: dataset.Corpus, mcp, psql: db.Psql, sidecar, token: str,
               state_path: pathlib.Path, *, settle_timeout: float = 3600.0,
               retry_pause: float = 10.0, sleep: Callable = time.sleep,
               clock: Callable = time.monotonic, out: Callable = print) -> dict:
    """Gate, write missing documents, wait for settling, verify, and write the state file."""
    started_at = _now()
    present, info = gates(corpus, psql, sidecar, token)
    todo = [corpus.documents[key] for key in sorted(corpus.documents) if key not in present]
    begin = clock()
    verified = False
    for index, doc in enumerate(todo, start=1):
        sent = _write_one(mcp, psql, doc, sleep, retry_pause)
        if sent and not verified:
            if not _topic_exists(psql, doc.key):
                raise IngestError("write did not arrive in the guarded database")
            verified = True
        if index % _PROGRESS_EVERY == 0:
            elapsed = max(clock() - begin, 1e-9)
            rate = index / elapsed
            out(f"ingest: {index}/{len(todo)} documents, {rate:.2f}/s, "
                f"~{(len(todo) - index) / rate:.0f} s left")
    _settle(psql, settle_timeout, sleep, clock, out)
    result = integrity(corpus, psql, info["max_chars"], out)
    state = {"dataset_sha256": corpus.sha256, "limit_questions": corpus.limit_questions,
             "documents": len(corpus.documents), "sidecar_info": info, "integrity": result,
             "started_at": started_at, "finished_at": _now()}
    state_path = pathlib.Path(state_path)
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(json.dumps(state, indent=2), encoding="utf-8")
    return state
