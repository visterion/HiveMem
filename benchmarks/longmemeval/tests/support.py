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
