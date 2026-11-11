"""The five retrieval arms, their exact-scan SQL, plan check and reply validation.

A degraded measurement (keyword-only ``search`` fallback, an index scan in a
control arm, a changed embedding model) is turned into ``ArmError`` and never
reaches ``rankings.jsonl``.
"""

import math
import pathlib
import re
import time
from collections.abc import Callable, Collection, Mapping, Sequence

import bm25
import dataset
import db
import ingest
import jsonl

ARMS = ("bm25", "cosine", "chunkmax", "hivemem", "hivemem-semantic")
LIMIT = 50
_TOLERANCE = 1e-4
_WEIGHTS = ("weight_keyword", "weight_recency", "weight_importance", "weight_popularity",
            "weight_graph_proximity")


class ArmError(RuntimeError):
    """A gate, plan, reply or ranking check of the retrieval stage failed."""


def vector_literal(vector: Sequence[float]) -> str:
    """pgvector text literal of ``vector``."""
    return "[" + ",".join(repr(float(x)) for x in vector) + "]"


_PREFIX = ("BEGIN;\nSET LOCAL enable_indexscan = off;\nSET LOCAL enable_bitmapscan = off;\n")

_COSINE = """SELECT c.topic, 1 - (c.embedding <=> q.v) AS sem
FROM (SELECT '{literal}'::vector AS v) q, cells c
WHERE c.realm = 'longmemeval' AND (c.valid_until IS NULL OR c.valid_until > now()) AND c.status = 'committed'
  AND c.embedding IS NOT NULL
ORDER BY sem DESC, c.id
LIMIT 50"""

_CHUNKMAX = """SELECT c.topic, GREATEST(COALESCE(max(1 - (ch.embedding <=> q.v)), 0), 1 - (c.embedding <=> q.v)) AS sem
FROM (SELECT '{literal}'::vector AS v) q,
     cells c LEFT JOIN cell_chunks ch ON ch.cell_id = c.id AND ch.embedding IS NOT NULL
WHERE c.realm = 'longmemeval' AND (c.valid_until IS NULL OR c.valid_until > now()) AND c.status = 'committed'
GROUP BY c.id, c.topic, c.embedding, q.v
HAVING c.embedding IS NOT NULL OR count(ch.cell_id) > 0
ORDER BY sem DESC, c.id
LIMIT 50"""


def _script(select: str, literal: str, explain: bool) -> str:
    return _PREFIX + ("EXPLAIN " if explain else "") + select.format(literal=literal) + ";\nCOMMIT;\n"


def cosine_script(literal: str, explain: bool = False) -> str:
    """Exact cosine scan over the cell vectors, one transaction with index scans off."""
    return _script(_COSINE, literal, explain)


def chunkmax_script(literal: str, explain: bool = False) -> str:
    """Exact scan taking the best of the cell vector and its chunk vectors."""
    return _script(_CHUNKMAX, literal, explain)


def check_plan(plan: str) -> None:
    """Raise ``ArmError`` when the plan uses an index scan."""
    for marker in ("Index Scan", "Index Only Scan", "Bitmap"):
        if marker in plan:
            raise ArmError(f"control arm plan contains {marker!r}: {plan.strip()}")


_VECTOR_LITERAL = re.compile(r"\[[0-9eE+\-.,]{201,}\]")


def redact_plan(plan: str) -> str:
    """The plan with every query-vector literal (a bracketed number run over 200 characters)
    replaced by ``[<query vector>]``, for storage; ``check_plan`` reads the full text."""
    return _VECTOR_LITERAL.sub("[<query vector>]", plan)


def search_arguments(arm: str, question: str) -> dict:
    """Arguments of the ``search`` tool for a ``hivemem`` arm."""
    arguments = {"query": question, "limit": LIMIT, "where": {"realm": ingest.REALM},
                 "include": ["scores"]}
    if arm == "hivemem-semantic":
        arguments["weight_semantic"] = 1
        for name in _WEIGHTS:
            arguments[name] = 0
    elif arm != "hivemem":
        raise ArmError(f"no search arguments for arm {arm!r}")
    return arguments


def _number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def validate_hits(arm: str, hits: Sequence[Mapping]) -> None:
    """Reject a reply that fell back to keyword-only ranking or ignored the weights."""
    if not hits:
        return
    for hit in hits:
        if not _number(hit.get("score_semantic")) or not _number(hit.get("score_total")):
            raise ArmError(f"{arm}: hit {hit.get('topic')!r} lacks numeric score_semantic/score_total")
    for hit in hits:
        if not (math.isfinite(hit["score_semantic"]) and math.isfinite(hit["score_total"])):
            raise ArmError(f"{arm}: hit {hit.get('topic')!r} has a non-finite score")
    if not any(hit["score_semantic"] > 0 for hit in hits):
        raise ArmError(f"{arm}: no hit has score_semantic > 0 (keyword-only fallback?)")
    if arm == "hivemem-semantic":
        for hit in hits:
            if not abs(hit["score_total"] - hit["score_semantic"]) <= _TOLERANCE:
                raise ArmError(f"{arm}: weights not honoured for {hit.get('topic')!r}: total "
                               f"{hit['score_total']} vs semantic {hit['score_semantic']}")


def to_ranking(topics: Sequence[str], known: Collection[str]) -> list[str]:
    """Map topics to document keys; unknown or repeated topics raise ``ArmError``."""
    seen: set[str] = set()
    for topic in topics:
        if not isinstance(topic, str) or topic not in known:
            raise ArmError(f"topic {topic!r} is not a corpus document")
        if topic in seen:
            raise ArmError(f"document {topic} occurs twice in one ranking")
        seen.add(topic)
    return list(topics)


def load_rankings(path: pathlib.Path) -> dict[tuple[str, str], dict]:
    """Stored records keyed by ``(question_id, arm)``."""
    return {(r["question_id"], r["arm"]): r for r in jsonl.read(path)}


def _sql_topics(psql: db.Psql, script: str) -> list[str]:
    return [row[0] for row in psql.rows(script) if len(row) == 2]


def _gate(corpus, psql, sidecar, token, ingest_state) -> dict:
    ingest.same_instance(psql, token)
    if ingest_state.get("dataset_sha256") != corpus.sha256:
        raise ArmError("ingest state belongs to a different dataset")
    # The chunk candidate pool of `search` is global: a foreign cell changes `hivemem`.
    ingest.no_foreign_cells(psql)
    recorded = ingest_state["sidecar_info"]
    ingest.integrity(corpus, psql, recorded["max_chars"])
    info = sidecar.info()
    if info.get("model") != recorded["model"]:
        raise ArmError(f"embedding model changed: {info.get('model')!r} != {recorded['model']!r}")
    ingest.check_embedding_model(psql, info)
    return info


def _hivemem_record(arm, question, mcp, corpus, clock) -> dict:
    begin = clock()
    hits = mcp.search(search_arguments(arm, question.text))
    latency = (clock() - begin) * 1000.0
    validate_hits(arm, hits)
    ranking = to_ranking([hit.get("topic") for hit in hits], corpus.documents)
    recency = [hit["score_recency"] for hit in hits if _number(hit.get("score_recency"))]
    return {"ranking": ranking, "latency_ms": latency if arm == "hivemem" else None,
            "hits_with_match": sum(1 for hit in hits if hit.get("match")),
            "recency_min": min(recency) if recency else None,
            "recency_max": max(recency) if recency else None}


def run_retrieve(corpus: dataset.Corpus, mcp, psql: db.Psql, sidecar, token: str,
                 ingest_state: Mapping, run_dir: pathlib.Path, *, arms: Sequence[str] = ARMS,
                 clock: Callable = time.perf_counter, out: Callable = print) -> dict:
    """Rank every question with every requested arm, appending each record at once.

    Returns the plan texts (vector literals redacted), the sidecar ``/info``
    reply and the arm definitions for the run metadata. Pairs already in
    ``rankings.jsonl`` are skipped; when nothing is pending for the SQL arms the
    plans are not re-taken (``{}``). When ``/info`` changed during the run,
    ``rankings.jsonl`` is moved to ``rankings.invalid.jsonl`` before ``ArmError``.
    """
    unknown = [arm for arm in arms if arm not in ARMS]
    if unknown:
        raise ArmError(f"unknown arm(s) {unknown}; known: {list(ARMS)}")
    start_info = _gate(corpus, psql, sidecar, token, ingest_state)
    path = pathlib.Path(run_dir) / "rankings.jsonl"
    done = set(load_rankings(path))
    wanted = [arm for arm in ARMS if arm in arms]
    pending = [(q, arm) for q in corpus.questions for arm in wanted if (q.id, arm) not in done]
    plans: dict[str, str] = {}
    first_vector = None
    sql_pending = [q for q, arm in pending if arm in ("cosine", "chunkmax")]
    if sql_pending:
        first_vector = sidecar.embed_query(sql_pending[0].text)
        literal = vector_literal(first_vector)
        for name, builder in (("cosine", cosine_script), ("chunkmax", chunkmax_script)):
            if name in wanted:
                plan = psql.run(builder(literal, explain=True))
                check_plan(plan)
                plans[name] = redact_plan(plan)
    bm = bm25.Bm25({key: doc.text for key, doc in corpus.documents.items()}) \
        if any(arm == "bm25" for _, arm in pending) else None
    vectors: dict[str, str] = {}
    if first_vector is not None:
        vectors[sql_pending[0].id] = vector_literal(first_vector)
    for number, (question, arm) in enumerate(pending, start=1):
        record = {"question_id": question.id, "arm": arm, "latency_ms": None,
                  "hits_with_match": None, "recency_min": None, "recency_max": None}
        if arm == "bm25":
            record["ranking"] = to_ranking(bm.rank(question.text, LIMIT), corpus.documents)
        elif arm in ("cosine", "chunkmax"):
            if question.id not in vectors:
                vectors[question.id] = vector_literal(sidecar.embed_query(question.text))
            builder = cosine_script if arm == "cosine" else chunkmax_script
            record["ranking"] = to_ranking(_sql_topics(psql, builder(vectors[question.id])),
                                           corpus.documents)
        else:
            record.update(_hivemem_record(arm, question, mcp, corpus, clock))
        jsonl.append(path, record)
        if number % 50 == 0:
            out(f"retrieve: {number}/{len(pending)} pairs")
    if sidecar.info() != start_info:
        if path.exists():
            path.replace(path.with_name("rankings.invalid.jsonl"))
        raise ArmError("sidecar /info changed during the run; rankings.jsonl was moved to "
                       "rankings.invalid.jsonl and no record of it counts; use a new --run-id")
    return {"plans": plans, "sidecar_info": start_info,
            "arms": {"bm25": "BM25 over rendered documents", "cosine": "exact cosine over cell vectors",
                     "chunkmax": "exact cosine, max over cell and chunk vectors",
                     "hivemem": "HiveMem search, default weights",
                     "hivemem-semantic": "HiveMem search, semantic weight only"}}
