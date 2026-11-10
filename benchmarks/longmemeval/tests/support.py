"""Test support: sys.path bootstrap and the synthetic LongMemEval fixture.

Every fixture turn is hand-written and starts with ``SYNTHETIC:``. Nothing here
is copied from the real dataset.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import dataset  # noqa: E402


def _turns(topic: str) -> list[dict]:
    return [
        {"role": "user", "content": f"SYNTHETIC: question about {topic}."},
        {"role": "assistant", "content": f"SYNTHETIC: invented reply about {topic}."},
    ]


_SESSIONS = {
    "answer_tea_1": _turns("tea"),
    "answer_tea_2": _turns("oolong"),
    "noise_bike_3": _turns("bicycles"),
    "answer_rain_4": _turns("rain"),
    "answer_count_5": _turns("counting"),
    "answer_abs_6": _turns("weather"),
}
_SESSIONS["noise_twin_9"] = _turns("tea")


def _row(qid, qtype, question, qdate, answer, answer_ids, haystack):
    return {
        "question_id": qid,
        "question_type": qtype,
        "question": question,
        "question_date": qdate,
        "answer": answer,
        "answer_session_ids": list(answer_ids),
        "haystack_session_ids": [sid for sid, _ in haystack],
        "haystack_dates": [date for _, date in haystack],
        "haystack_sessions": [_SESSIONS[sid] for sid, _ in haystack],
    }


def fixture_rows() -> list[dict]:
    return [
        _row("q_tea", "knowledge-update", "SYNTHETIC: which tea does the user drink?",
             "2023/06/01 (Thu) 10:00", "oolong", ["answer_tea_1", "answer_tea_2"],
             [("answer_tea_1", "2023/05/02 (Tue) 09:00"),
              ("answer_tea_2", "2023/05/20 (Sat) 02:21"),
              ("noise_bike_3", "2023/05/10 (Wed) 08:00")]),
        _row("q_count", "multi-session", "SYNTHETIC: how many items were counted?",
             "2023/06/02 (Fri) 10:00", 3, ["answer_count_5"],
             [("answer_count_5", "2023/05/15 (Mon) 12:00"),
              ("noise_twin_9", "2023/04/01 (Sat) 07:00"),
              ("noise_bike_3", "2023/05/01 (Mon) 08:00")]),
        _row("q_parrot_abs", "multi-session", "SYNTHETIC: what is the name of the user's dog?",
             "2023/06/03 (Sat) 10:00", "The user never mentioned a dog.", ["answer_abs_6"],
             [("answer_abs_6", "2023/05/05 (Fri) 18:00"),
              ("answer_rain_4", "2023/05/06 (Sat) 18:00")]),
        _row("q_rain", "single-session-user", "SYNTHETIC: where did it rain?",
             "2023/06/04 (Sun) 10:00", "Exampleville", ["answer_rain_4"],
             [("answer_rain_4", "2023/05/07 (Sun) 18:00"),
              ("answer_count_5", "2023/05/15 (Mon) 12:00")]),
    ]


def write_fixture(directory: str) -> str:
    path = os.path.join(directory, "fixture.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(fixture_rows(), handle)
    return path


def fixture_corpus(limit: int | None = None) -> "dataset.Corpus":
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        return dataset.load(write_fixture(tmp), limit_questions=limit)


def key_of(session_id: str) -> str:
    return dataset.document_key(dataset.render(_SESSIONS[session_id]))


class FakeRunner:
    """Stand-in for ``subprocess.run`` that feeds the script to a responder.

    The responder takes the script text and returns a ``str`` (stdout of a
    successful run), a ``(returncode, stdout, stderr)`` tuple, or an exception
    instance, which is raised. Each call is recorded in ``calls`` as
    ``(command, script)``.
    """

    def __init__(self, responder):
        self._responder = responder
        self.calls: list[tuple[list[str], str]] = []

    def __call__(self, command, input=None, capture_output=False, text=False, **kwargs):
        import subprocess
        self.calls.append((list(command), input))
        result = self._responder(input)
        if isinstance(result, BaseException):
            raise result
        if isinstance(result, tuple):
            code, out, err = result
        else:
            code, out, err = 0, result, ""
        return subprocess.CompletedProcess(list(command), code, out, err)


def cell(topic, realm="longmemeval", status="committed", valid_until_null=True, vectorless=False):
    """One in-memory cell row for ``FakeBenchDb``."""
    return {"topic": topic, "realm": realm, "status": status,
            "valid_until_null": valid_until_null, "vectorless": vectorless}


class FakeBenchDb:
    """Runner for ``db.Psql`` answering the SQL constants of ``ingest`` from memory.

    State lives in plain attributes: ``cells`` (list of dicts, see ``cell``),
    ``token_hashes``, ``chunks`` (total, without vector), ``unsettled`` (values
    popped per UNSETTLED_SQL call, the last one repeats) and ``scripts``.
    Output mimics ``psql -At -F '\\t'``: tab-separated, booleans as t / f.
    """

    def __init__(self, cells=None, token_hashes=(), chunks=(0, 0), unsettled=(0,),
                 plan="Limit\n  ->  Sort\n        ->  Seq Scan on cells c", similarity=None):
        self.plan = plan
        self.similarity = dict(similarity or {})
        self.cells = list(cells or [])
        self.token_hashes = set(token_hashes)
        self.chunks = tuple(chunks)
        self.unsettled = list(unsettled)
        self.scripts: list[str] = []

    def insert(self, topic, realm="longmemeval", vectorless=False):
        self.cells.append(cell(topic, realm=realm, vectorless=vectorless))

    @staticmethod
    def _bool(value):
        return "t" if value else "f"

    @staticmethod
    def _literal(script, template):
        import re
        pattern = re.escape(template).replace(re.escape("{hash}"), "'(.*)'") \
                                     .replace(re.escape("{topic}"), "'(.*)'")
        match = re.fullmatch(pattern, script)
        return match.group(1).replace("''", "'") if match else None

    def _answer(self, script):
        import ingest
        if script == ingest.FOREIGN_SQL:
            return f"{sum(1 for c in self.cells if c['realm'] != 'longmemeval')}\n"
        if script == ingest.REALM_CELLS_SQL:
            mine = sorted((c for c in self.cells if c["realm"] == "longmemeval"),
                          key=lambda c: c["topic"])
            return "".join(f"{c['topic']}\t{c['status']}\t{self._bool(c['valid_until_null'])}\t"
                           f"{self._bool(c['vectorless'])}\n" for c in mine)
        if script == ingest.UNSETTLED_SQL:
            value = self.unsettled[0] if len(self.unsettled) == 1 else self.unsettled.pop(0)
            return f"{value}\n"
        if script == ingest.CHUNKS_SQL:
            return f"{self.chunks[0]}\t{self.chunks[1]}\n"
        if script == ingest.SPAN_SQL:
            return "2026-01-01 00:00:00+00\t2026-01-01 00:05:00+00\n"
        token = self._literal(script, ingest.TOKEN_SQL)
        if token is not None:
            return f"{1 if token in self.token_hashes else 0}\n"
        topic = self._literal(script, ingest.TOPIC_SQL)
        if topic is not None:
            return f"{sum(1 for c in self.cells if c['realm'] == 'longmemeval' and c['topic'] == topic)}\n"
        if "EXPLAIN" in script and "<=>" in script:
            return f"BEGIN\nSET\nSET\n{self.plan}\nCOMMIT\n"
        if "<=>" in script:
            mine = [c["topic"] for c in self.cells if c["realm"] == "longmemeval"]
            ranked = sorted(mine, key=lambda t: (-self.similarity.get(t, 0.5), t))[:50]
            return "BEGIN\nSET\nSET\n" + "".join(
                f"{t}\t{self.similarity.get(t, 0.5)}\n" for t in ranked) + "COMMIT\n"
        raise AssertionError(f"FakeBenchDb: unknown script {script!r}")

    def __call__(self, command, input=None, capture_output=False, text=False, **kwargs):
        import subprocess
        self.scripts.append(input)
        return subprocess.CompletedProcess(list(command), 0, self._answer(input), "")


class FakeMcp:
    """Records ``add_cell`` keyword dicts and ``search`` argument dicts, in order, in ``calls``.

    ``add_cell`` inserts a committed cell into ``db`` (when given) unless
    ``fail(call_index, kwargs)`` returns an exception, which is then raised. A
    ``(exception, True)`` tuple inserts the cell first and then raises.
    ``hits(arguments)`` supplies what ``search`` returns (default: no hits).
    """

    def __init__(self, db=None, fail=None, hits=None):
        self.db = db
        self.fail = fail
        self.hits = hits
        self.calls: list[dict] = []

    def add_cell(self, **kwargs):
        index = len(self.calls)
        self.calls.append(dict(kwargs))
        outcome = self.fail(index, kwargs) if self.fail else None
        insert_first = False
        if isinstance(outcome, tuple):
            outcome, insert_first = outcome
        if (outcome is None or insert_first) and self.db is not None:
            self.db.insert(kwargs["topic"], realm=kwargs["realm"])
        if outcome is not None:
            raise outcome
        return {"inserted": True}

    def search(self, arguments):
        self.calls.append(dict(arguments))
        return self.hits(arguments) if self.hits else []


class FakeSidecar:
    """Stand-in for ``hivemem.Sidecar``."""

    def __init__(self, info=None, vector=None):
        self._info = {"model": "synthetic-model", "max_chars": 50000} if info is None else info
        self._vector = [0.0, 1.0] if vector is None else vector

    def info(self):
        return dict(self._info)

    def embed_query(self, text):
        return list(self._vector)
