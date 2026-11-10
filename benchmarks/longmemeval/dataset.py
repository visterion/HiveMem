"""Load, merge and select the LongMemEval dataset.

Sessions are merged into content-level documents: two sessions that render to
the same text are one document, whatever their session ids. Document dates are
the earliest haystack date over the whole dataset, so they do not depend on
which questions are selected.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import re
from collections.abc import Callable, Mapping, Sequence

EXPECTED_SHA256 = "d6f21ea9d60a0d56f34a05b609c79c88a451d2ae03597821ea3d5a9678c3a442"

_REQUIRED = (
    "question_id", "question_type", "question", "question_date", "answer",
    "answer_session_ids", "haystack_session_ids", "haystack_dates", "haystack_sessions",
)
_DATE_RE = re.compile(r"^(\d{4})/(\d{2})/(\d{2}) \([A-Za-z]{3}\) (\d{2}):(\d{2})$")


class DatasetError(RuntimeError):
    """The dataset file is missing, malformed or inconsistent."""


@dataclasses.dataclass(frozen=True)
class Document:
    key: str
    text: str
    date: str
    session_ids: tuple[str, ...]
    turns: tuple[tuple[str, str], ...]


@dataclasses.dataclass(frozen=True)
class Question:
    id: str
    type: str
    text: str
    date: str
    answer: str
    is_abstention: bool
    gold: frozenset[str]
    haystack: frozenset[str]
    haystack_dates: Mapping[str, str]


@dataclasses.dataclass(frozen=True)
class Corpus:
    sha256: str
    limit_questions: int | None
    questions: tuple[Question, ...]
    documents: Mapping[str, Document]


def render(session: Sequence[Mapping[str, str]]) -> str:
    """Render a session as plain text, one line per turn, both roles."""
    return "\n".join(f"{turn['role']}: {turn['content']}" for turn in session)


def document_key(text: str) -> str:
    return "d" + hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def to_iso(date: str) -> str:
    """Convert '2023/05/20 (Sat) 02:21' to '2023-05-20T02:21:00Z'."""
    match = _DATE_RE.match(date)
    if not match:
        raise DatasetError(f"unparseable date: {date!r}")
    year, month, day, hour, minute = match.groups()
    return f"{year}-{month}-{day}T{hour}:{minute}:00Z"


def date_for(question: Question, document: Document) -> str:
    """Date of a document in the question's own haystack, else the document's own."""
    return question.haystack_dates.get(document.key, document.date)


def stratified(questions: Sequence[Question], limit: int) -> list[Question]:
    """Pick up to `limit` questions round-robin over the sorted question types."""
    groups: dict[str, list[Question]] = {}
    for question in questions:
        groups.setdefault(question.type, []).append(question)
    queues = [sorted(groups[name], key=lambda q: q.id) for name in sorted(groups)]
    picked: list[Question] = []
    position = 0
    while len(picked) < limit and any(position < len(queue) for queue in queues):
        for queue in queues:
            if position < len(queue) and len(picked) < limit:
                picked.append(queue[position])
        position += 1
    return picked


def _read(path: str) -> tuple[bytes, list]:
    try:
        with open(path, "rb") as handle:
            raw = handle.read()
    except OSError as error:
        raise DatasetError(f"cannot read dataset {path}: {error}") from error
    try:
        rows = json.loads(raw)
    except ValueError as error:
        raise DatasetError(f"dataset {path} is not valid JSON: {error}") from error
    if not isinstance(rows, list):
        raise DatasetError(f"dataset {path} must be a JSON list of questions")
    return raw, rows


def load(path: str, limit_questions: int | None = None,
         key_fn: Callable[[str], str] = document_key) -> Corpus:
    """Load the dataset, merge sessions into documents and select questions."""
    if limit_questions is not None and limit_questions < 1:
        raise DatasetError(f"limit_questions must be at least 1, got {limit_questions}")
    raw, rows = _read(path)

    text_by_key: dict[str, str] = {}
    key_by_session: dict[str, str] = {}
    turns_by_key: dict[str, tuple[tuple[str, str], ...]] = {}
    sessions_by_key: dict[str, set[str]] = {}
    earliest: dict[str, str] = {}
    questions: list[Question] = []

    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            raise DatasetError(f"dataset {path}: entry {index} is not an object")
        missing = [name for name in _REQUIRED if name not in row]
        if missing:
            raise DatasetError(f"dataset {path}: entry {index} missing {', '.join(missing)}")
        ids, dates, sessions = (row["haystack_session_ids"], row["haystack_dates"],
                                row["haystack_sessions"])
        if not len(ids) == len(dates) == len(sessions):
            raise DatasetError(f"dataset {path}: question {row['question_id']} has "
                               "haystack lists of different length")
        haystack_dates: dict[str, str] = {}
        for session_id, date, session in zip(ids, dates, sessions):
            text = render(session)
            key = key_fn(text)
            if text_by_key.setdefault(key, text) != text:
                raise DatasetError(f"document key collision on {key}")
            if key_by_session.setdefault(session_id, key) != key:
                raise DatasetError(f"session {session_id} appears with two different texts")
            turns_by_key.setdefault(key, tuple((t["role"], t["content"]) for t in session))
            sessions_by_key.setdefault(key, set()).add(session_id)
            if key not in earliest or to_iso(date) < to_iso(earliest[key]):
                earliest[key] = date
            if key not in haystack_dates or to_iso(date) < to_iso(haystack_dates[key]):
                haystack_dates[key] = date
        try:
            gold = frozenset(key_by_session[sid] for sid in row["answer_session_ids"])
        except KeyError as error:
            raise DatasetError(f"dataset {path}: question {row['question_id']} names "
                               f"answer session {error.args[0]} outside its haystack") from error
        questions.append(Question(
            id=row["question_id"], type=row["question_type"], text=row["question"],
            date=row["question_date"], answer=str(row["answer"]),
            is_abstention="_abs" in row["question_id"], gold=gold,
            haystack=frozenset(haystack_dates), haystack_dates=haystack_dates))

    selected = questions if limit_questions is None else stratified(questions, limit_questions)
    needed = set().union(*(q.haystack for q in selected)) if selected else set()
    documents = {
        key: Document(key=key, text=text_by_key[key], date=earliest[key],
                      session_ids=tuple(sorted(sessions_by_key[key])), turns=turns_by_key[key])
        for key in sorted(needed)
    }
    return Corpus(sha256=hashlib.sha256(raw).hexdigest(), limit_questions=limit_questions,
                  questions=tuple(selected), documents=documents)
