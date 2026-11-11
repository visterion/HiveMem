"""Answer stage: upstream prompts, strict verdict parsing, append-only caches, reader/judge run.

Answers and verdicts must be exactly reproducible from the upstream prompts, a
failed model call stops the run instead of counting as a wrong answer, and
identical prompts are paid for once: they are deduplicated by cache key before
they are submitted to the thread pool.
"""

from __future__ import annotations

import concurrent.futures
import hashlib
import json
import pathlib
import re
import threading
from collections.abc import Callable, Mapping, Sequence

import dataset
import jsonl
import llm
import metrics

QA_ARMS = ("bm25", "cosine", "hivemem", "oracle")
TOP_K = 5

# The six templates below are copied verbatim from LongMemEval (MIT License,
# Copyright (c) 2024 Di Wu): src/evaluation/evaluate_qa.py (judge prompts) and
# src/generation/run_generation.py (reader prompt). The SHA-256 test guards them.
READER_TEMPLATE = (
    'I will give you several history chats between you and a user. Please answer the question based on the relevant chat history. Answer the question step by step: first extract all the relevant information, and then reason over the information to get the answer.\n\n\nHistory Chats:\n\n{}\n\nCurrent Date: {}\nQuestion: {}\nAnswer (step by step):'
)

JUDGE_TEMPLATES = {
    'default': (
        'I will give you a question, a correct answer, and a response from a model. Please answer yes if the response contains the correct answer. Otherwise, answer no. If the response is equivalent to the correct answer or contains all the intermediate steps to get the correct answer, you should also answer yes. If the response only contains a subset of the information required by the answer, answer no. \n\nQuestion: {}\n\nCorrect Answer: {}\n\nModel Response: {}\n\nIs the model response correct? Answer yes or no only.'
    ),
    'temporal-reasoning': (
        "I will give you a question, a correct answer, and a response from a model. Please answer yes if the response contains the correct answer. Otherwise, answer no. If the response is equivalent to the correct answer or contains all the intermediate steps to get the correct answer, you should also answer yes. If the response only contains a subset of the information required by the answer, answer no. In addition, do not penalize off-by-one errors for the number of days. If the question asks for the number of days/weeks/months, etc., and the model makes off-by-one errors (e.g., predicting 19 days when the answer is 18), the model's response is still correct. \n\nQuestion: {}\n\nCorrect Answer: {}\n\nModel Response: {}\n\nIs the model response correct? Answer yes or no only."
    ),
    'knowledge-update': (
        'I will give you a question, a correct answer, and a response from a model. Please answer yes if the response contains the correct answer. Otherwise, answer no. If the response contains some previous information along with an updated answer, the response should be considered as correct as long as the updated answer is the required answer.\n\nQuestion: {}\n\nCorrect Answer: {}\n\nModel Response: {}\n\nIs the model response correct? Answer yes or no only.'
    ),
    'single-session-preference': (
        "I will give you a question, a rubric for desired personalized response, and a response from a model. Please answer yes if the response satisfies the desired response. Otherwise, answer no. The model does not need to reflect all the points in the rubric. The response is correct as long as it recalls and utilizes the user's personal information correctly.\n\nQuestion: {}\n\nRubric: {}\n\nModel Response: {}\n\nIs the model response correct? Answer yes or no only."
    ),
    'abstention': (
        'I will give you an unanswerable question, an explanation, and a response from a model. Please answer yes if the model correctly identifies the question as unanswerable. The model could say that the information is incomplete, or some other information is given but the asked information is not.\n\nQuestion: {}\n\nExplanation: {}\n\nModel Response: {}\n\nDoes the model correctly identify the question as unanswerable? Answer yes or no only.'
    ),
}

_DEFAULT_TYPES = ("single-session-user", "single-session-assistant", "multi-session")
_OWN_TYPES = ("temporal-reasoning", "knowledge-update", "single-session-preference")
_VERDICT_RE = re.compile(r"^\W*(yes|no)\b", re.IGNORECASE)
_PREFERENCE_TYPE = "single-session-preference"


class QaError(RuntimeError):
    """The answer stage cannot proceed (missing ranking, unknown question type)."""


def judge_template(question_type: str, is_abstention: bool) -> str:
    """Returns the upstream judge template for a question."""
    if is_abstention:
        return JUDGE_TEMPLATES["abstention"]
    if question_type in _DEFAULT_TYPES:
        return JUDGE_TEMPLATES["default"]
    if question_type in _OWN_TYPES:
        return JUDGE_TEMPLATES[question_type]
    raise QaError(f"no judge template for question type {question_type!r}")


def judge_prompt(question, response: str) -> str:
    """Returns the judge prompt for ``response`` to ``question``."""
    template = judge_template(question.type, question.is_abstention)
    return template.format(question.text, question.answer, response)


def parse_verdict(reply: str) -> bool | None:
    """Returns True for a leading yes, False for a leading no, None for anything else."""
    match = _VERDICT_RE.match(reply)
    if match is None:
        return None
    return match.group(1).lower() == "yes"


def context(question, arm: str, rankings: Mapping, corpus) -> list[tuple[str, dataset.Document]]:
    """Returns ``(date, document)`` pairs in prompt order: date ascending, ties by rank."""
    if arm == "oracle":
        keys = sorted(question.gold)
    else:
        record = rankings.get((question.id, arm))
        if record is None:
            raise QaError(f"no ranking for question {question.id} and arm {arm}")
        keys = list(record["ranking"][:TOP_K])
    pairs = [(dataset.date_for(question, corpus.documents[key]), corpus.documents[key])
             for key in keys]
    return sorted(pairs, key=lambda pair: pair[0])  # stable: ties keep rank order


def reader_prompt(question, context: Sequence[tuple[str, dataset.Document]]) -> str:
    """Returns the upstream reader prompt over the given context."""
    history = ""
    for position, (date, document) in enumerate(context, start=1):
        turns = "".join("\n\n{}: {}".format(role, content.strip())
                        for role, content in document.turns)
        history += "\n### Session {}:\nSession Date: {}\nSession Content:\n{}\n".format(
            position, date, turns)
    return READER_TEMPLATE.format(history, question.date, question.text)


def cache_key(model: str, system_prompt: str, prompt: str) -> str:
    """Returns the SHA-256 of model, system prompt and prompt."""
    return hashlib.sha256("\x1f".join((model, system_prompt, prompt)).encode("utf-8")).hexdigest()


class Cache:
    """Append-only JSON-lines cache keyed by ``cache_key``; the later entry of a key wins."""

    def __init__(self, path: pathlib.Path, lock: threading.Lock, enabled: bool = True) -> None:
        self._path = pathlib.Path(path)
        self._lock = lock
        self._enabled = enabled
        self._entries: dict[str, dict] = {}
        # Always read: it repairs a cut-off last line, even when entries are not served.
        for entry in jsonl.read(self._path):
            if enabled:
                self._entries[entry["key"]] = entry

    def get(self, key: str) -> dict | None:
        """Returns the cached entry, or None (always None when reading is disabled)."""
        if not self._enabled:
            return None
        with self._lock:
            return self._entries.get(key)

    def put(self, key: str, entry: dict) -> None:
        """Appends the entry as one JSON line and remembers it."""
        record = {**entry, "key": key}
        with self._lock:
            jsonl.append(self._path, record)
            self._entries[key] = record


def _resolve(items: Mapping[str, tuple[str, str, Callable | None]], cache: Cache,
             call: Callable, workers: int) -> dict[str, dict]:
    """Returns the cache entry of every key of ``items`` (``key -> (model, prompt, validate)``).

    Each distinct key is answered once, from the cache or by one model call. The
    first failed call is raised and the calls not yet started are cancelled.
    """
    entries: dict[str, dict] = {}
    todo = []
    for key, item in items.items():
        cached = cache.get(key)
        if cached is not None:
            entries[key] = cached
        else:
            todo.append(key)

    def work(key: str) -> dict:
        model, prompt, validate = items[key]
        kwargs = {"system_prompt": llm.SYSTEM_PROMPT}
        if validate is not None:
            kwargs["validate"] = validate
        reply = call(model, prompt, **kwargs)
        entry = {"key": key, "model": model, "result": reply.text,
                 "model_usage": reply.model_usage, "usage": reply.usage}
        cache.put(key, entry)
        return entry

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = {pool.submit(work, key): key for key in todo}
        try:
            for future in concurrent.futures.as_completed(futures):
                entries[futures[future]] = future.result()
        except BaseException:
            pool.shutdown(wait=True, cancel_futures=True)
            raise
    return entries


def _served(entries: Sequence[dict]) -> list[str]:
    return sorted({name for entry in entries for name in entry["model_usage"]})


def _strict(text: str) -> bool:
    return parse_verdict(text) is not None


def _accuracy(flags: Sequence[bool]) -> float | None:
    """Share of correct answers; ``None`` for an empty group (no answer is not a wrong one)."""
    return sum(flags) / len(flags) if flags else None


def _arm_summary(questions: Sequence, flags: Sequence[bool]) -> dict:
    by_type: dict[str, list[bool]] = {}
    for question, flag in zip(questions, flags):
        by_type.setdefault(question.type, []).append(flag)
    abstention = [flag for question, flag in zip(questions, flags) if question.is_abstention]
    return {"n": len(flags), "accuracy": _accuracy(flags),
            "by_type": {name: {"n": len(values), "accuracy": _accuracy(values)}
                        for name, values in sorted(by_type.items())},
            "abstention": {"n": len(abstention), "accuracy": _accuracy(abstention)}}


def run_qa(corpus, rankings: Mapping, run_dir: pathlib.Path, cache_dir: pathlib.Path, *,
           reader_model: str = "claude-haiku-5-5", judge_model: str = "claude-sonnet-5-5",
           arms: Sequence[str] = QA_ARMS, workers: int = 4, use_cache: bool = True,
           call: Callable = llm.call, out: Callable = print) -> dict:
    """Answers and judges every question for every arm; returns the ``qa.json`` content."""
    run_dir, cache_dir = pathlib.Path(run_dir), pathlib.Path(cache_dir)
    lock = threading.Lock()
    answers = Cache(cache_dir / "answers.jsonl", lock, use_cache)
    judgments = Cache(cache_dir / "judgments.jsonl", lock, use_cache)

    # Phase 1: every context and reader prompt; a missing ranking stops here.
    plan = {}
    for question in corpus.questions:
        for arm in arms:
            judge_template(question.type, question.is_abstention)  # unknown type stops early
            ctx = context(question, arm, rankings, corpus)
            plan[(question.id, arm)] = (ctx, reader_prompt(question, ctx))

    # Phase 2: distinct reader prompts.
    reader_items = {}
    for _, prompt in plan.values():
        key = cache_key(reader_model, llm.SYSTEM_PROMPT, prompt)
        reader_items.setdefault(key, (reader_model, prompt, None))
    out(f"reader: {len(reader_items)} distinct prompts for {len(plan)} question x arm pairs")
    reader_entries = _resolve(reader_items, answers, call, workers)

    # Phase 3: judge prompts from the answers.
    judge_items, judged = {}, {}
    for question in corpus.questions:
        for arm in arms:
            _, prompt = plan[(question.id, arm)]
            answer_key = cache_key(reader_model, llm.SYSTEM_PROMPT, prompt)
            judge_text = judge_prompt(question, reader_entries[answer_key]["result"])
            judge_key = cache_key(judge_model, llm.SYSTEM_PROMPT, judge_text)
            judge_items.setdefault(judge_key, (judge_model, judge_text, _strict))
            judged[(question.id, arm)] = (answer_key, judge_key)
    out(f"judge: {len(judge_items)} distinct prompts")
    judge_entries = _resolve(judge_items, judgments, call, workers)

    # Phase 4: write the files.
    lines, correct = [], {arm: [] for arm in arms}
    for question in corpus.questions:
        for arm in arms:
            answer_key, judge_key = judged[(question.id, arm)]
            verdict_raw = judge_entries[judge_key]["result"]
            verdict = parse_verdict(verdict_raw)
            if verdict is None:
                raise QaError(f"unusable judgment for question {question.id} and arm {arm}: "
                              f"{verdict_raw[:100]!r}")
            flag = verdict
            correct[arm].append(flag)
            lines.append({"question_id": question.id, "arm": arm,
                          "context": [document.key for _, document in plan[(question.id, arm)][0]],
                          "answer": reader_entries[answer_key]["result"],
                          "verdict_raw": verdict_raw, "correct": flag})
    summary = {
        "questions": len(corpus.questions),
        "reader": {"requested": reader_model,
                   "served": _served([reader_entries[a] for a, _ in judged.values()])},
        "judge": {"requested": judge_model,
                  "served": _served([judge_entries[j] for _, j in judged.values()])},
        "arms": {arm: _arm_summary(corpus.questions, correct[arm]) for arm in arms},
        "paired": {},
    }
    if "hivemem" in arms:
        for other in ("bm25", "cosine"):
            if other in arms:
                result = metrics.paired(correct["hivemem"], correct[other])
                summary["paired"][other] = {"b": result.b, "c": result.c,
                                            "z": result.z, "p": result.p}
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "qa.jsonl").write_text("".join(json.dumps(line) + "\n" for line in lines),
                                      encoding="utf-8")
    (run_dir / "qa.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    for arm in arms:
        accuracy = summary["arms"][arm]["accuracy"]
        out(f"{arm}: accuracy {'-' if accuracy is None else f'{accuracy:.3f}'}")
    return summary


def _negative_partner(question, eligible: Sequence):
    """Next eligible question of the same type (id order, cyclic) with a different answer."""
    same = sorted((q for q in eligible if q.type == question.type), key=lambda q: q.id)
    start = same.index(question)
    for offset in range(1, len(same)):
        other = same[(start + offset) % len(same)]
        if other.answer.strip().lower() != question.answer.strip().lower():
            return other
    return None


def run_selfcheck(corpus, cache_dir: pathlib.Path, *, judge_model: str = "claude-sonnet-5-5",
                  count: int = 40, workers: int = 4, use_cache: bool = True,
                  call: Callable = llm.call, out: Callable = print) -> dict:
    """Checks the judge: a gold answer must be judged yes, another question's gold answer no."""
    eligible = [q for q in corpus.questions
                if not q.is_abstention and q.type != _PREFERENCE_TYPE]
    checks = []  # (question id, kind, expected, judge prompt)
    for question in dataset.stratified(eligible, count):
        checks.append((question.id, "positive", True, judge_prompt(question, question.answer)))
        partner = _negative_partner(question, eligible)
        if partner is not None:
            checks.append((question.id, "negative", False, judge_prompt(question, partner.answer)))
    cache = Cache(pathlib.Path(cache_dir) / "judgments.jsonl", threading.Lock(), use_cache)
    items = {}
    for _, _, _, prompt in checks:
        items.setdefault(cache_key(judge_model, llm.SYSTEM_PROMPT, prompt),
                         (judge_model, prompt, _strict))
    entries = _resolve(items, cache, call, workers)
    disagreements, agreed = [], 0
    for question_id, kind, expected, prompt in checks:
        raw = entries[cache_key(judge_model, llm.SYSTEM_PROMPT, prompt)]["result"]
        if parse_verdict(raw) is expected:
            agreed += 1
        else:
            disagreements.append({"question_id": question_id, "kind": kind, "verdict_raw": raw})
    result = {"checks": len(checks), "agreed": agreed,
              "agreement": agreed / len(checks) if checks else 0.0,
              "disagreements": disagreements}
    out(f"judge self-check: {agreed}/{len(checks)} agreed ({result['agreement']:.3f})")
    return result
