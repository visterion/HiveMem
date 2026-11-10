"""Tests for qa.py: upstream templates, prompts, verdict parser, caches, answer stage."""

import dataclasses
import hashlib
import json
import pathlib
import re
import tempfile
import threading
import unittest
from unittest import mock

import support  # noqa: F401  (puts the harness directory on sys.path)

import dataset
import llm
import qa

READER_MODEL = "claude-haiku-5-5"
JUDGE_MODEL = "claude-sonnet-5-5"


class TemplateParityTest(unittest.TestCase):
    HASHES = {
        "default": "fba020ba3d57982efdc9a937c1c01f897b789a608c7f88e60244121f6505e5bc",
        "temporal-reasoning": "8d33a5fdd83afeeb4592454a965eab43d1fcb2dedc042d1d3892f4254be6c273",
        "knowledge-update": "183a9b3a6197ec620940f610cdc1207201ec98c1113dd633ea685cfc322fafac",
        "single-session-preference": "741ee3bcbea7ff5e8ed359acef61d2f8ded3de021bbcff6ee13de455f2e2aa9b",
        "abstention": "5c0b365a1e1d06db36377c735432b56e122ca3c428f89faf61d43a0d5a7e050b",
    }
    READER = "9e2b3110622929ab896696dd8937231c7436740ec3b9586f653f97346e19ab2c"

    def test_templates_match_upstream(self):
        digest = lambda text: hashlib.sha256(text.encode("utf-8")).hexdigest()
        self.assertEqual({name: digest(text) for name, text in qa.JUDGE_TEMPLATES.items()}, self.HASHES)
        self.assertEqual(digest(qa.READER_TEMPLATE), self.READER)


class JudgeTest(unittest.TestCase):
    def test_template_selection(self):
        for qtype in ("single-session-user", "single-session-assistant", "multi-session"):
            self.assertEqual(qa.judge_template(qtype, False), qa.JUDGE_TEMPLATES["default"])
        for qtype in ("temporal-reasoning", "knowledge-update", "single-session-preference"):
            self.assertEqual(qa.judge_template(qtype, False), qa.JUDGE_TEMPLATES[qtype])
        for qtype in ("multi-session", "temporal-reasoning", "unknown-type"):
            self.assertEqual(qa.judge_template(qtype, True), qa.JUDGE_TEMPLATES["abstention"])
        with self.assertRaises(qa.QaError):
            qa.judge_template("unknown-type", False)

    def test_judge_prompt(self):
        question = dataclasses.replace(
            support.fixture_corpus().questions[1],
            text="How many umbrellas do I own?", answer="3")
        self.assertEqual(
            qa.judge_prompt(question, "three"),
            qa.JUDGE_TEMPLATES["default"].format("How many umbrellas do I own?", "3", "three"))

    def test_parse_verdict(self):
        self.assertIs(qa.parse_verdict("Yes."), True)
        self.assertIs(qa.parse_verdict("no"), False)
        self.assertIs(qa.parse_verdict("  **Yes**"), True)
        self.assertIs(qa.parse_verdict("No, the user said it happened yesterday."), False)
        self.assertIsNone(qa.parse_verdict("Yesterday the user ..."))
        self.assertIsNone(qa.parse_verdict(""))
        self.assertIsNone(qa.parse_verdict("Nobody knows"))


class ReaderPromptTest(unittest.TestCase):
    def setUp(self):
        self.corpus = support.fixture_corpus()
        self.questions = {q.id: q for q in self.corpus.questions}

    def _context(self, qid, keys, arm="bm25"):
        rankings = {(qid, arm): {"ranking": keys}}
        return qa.context(self.questions[qid], arm, rankings, self.corpus)

    def test_reader_prompt_literal(self):
        question = self.questions["q_rain"]
        context = self._context("q_rain", [support.key_of("answer_count_5"),
                                           support.key_of("answer_rain_4")])
        prompt = qa.reader_prompt(question, context)
        self.assertEqual(prompt, (
            "I will give you several history chats between you and a user. Please answer the question based on the "
            "relevant chat history. Answer the question step by step: first extract all the relevant information, "
            "and then reason over the information to get the answer.\n\n\nHistory Chats:\n\n"
            "\n### Session 1:\nSession Date: 2023/05/07 (Sun) 18:00\nSession Content:\n"
            "\n\nuser: SYNTHETIC: question about rain.\n\nassistant: SYNTHETIC: invented reply about rain.\n"
            "\n### Session 2:\nSession Date: 2023/05/15 (Mon) 12:00\nSession Content:\n"
            "\n\nuser: SYNTHETIC: question about counting.\n\nassistant: SYNTHETIC: invented reply about counting.\n"
            "\n\nCurrent Date: 2023/06/04 (Sun) 10:00\nQuestion: SYNTHETIC: where did it rain?\n"
            "Answer (step by step):"))

    def test_own_haystack_date_wins(self):
        context = self._context("q_tea", [support.key_of("answer_tea_1")])
        self.assertEqual([date for date, _ in context], ["2023/05/02 (Tue) 09:00"])
        self.assertEqual(self.corpus.documents[support.key_of("noise_twin_9")].date,
                         "2023/04/01 (Sat) 07:00")

    def test_foreign_document_uses_document_date(self):
        context = self._context("q_rain", [support.key_of("noise_bike_3")])
        self.assertEqual([date for date, _ in context], ["2023/05/01 (Mon) 08:00"])

    def test_top_five_only(self):
        keys = sorted(self.corpus.documents)
        self.assertEqual(len(keys), 6)
        self.assertEqual(len(self._context("q_tea", keys)), 5)

    def test_oracle_uses_all_gold(self):
        tea = qa.context(self.questions["q_tea"], "oracle", {}, self.corpus)
        self.assertEqual({doc.key for _, doc in tea}, set(self.questions["q_tea"].gold))
        self.assertEqual(len(tea), 2)
        wide = dataclasses.replace(self.questions["q_tea"], gold=frozenset(self.corpus.documents))
        self.assertEqual(len(qa.context(wide, "oracle", {}, self.corpus)), 6)

    def test_content_is_stripped_per_turn(self):
        padded = dataset.Document(key="dpad", text="x", date="2023/01/01 (Sun) 00:00",
                                  session_ids=(), turns=(("user", "  SYNTHETIC: padded  "),))
        prompt = qa.reader_prompt(self.questions["q_rain"], [("2023/01/01 (Sun) 00:00", padded)])
        self.assertIn("\n\nuser: SYNTHETIC: padded\n", prompt)
        self.assertNotIn("  SYNTHETIC", prompt)


class CacheTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.path = pathlib.Path(self._tmp.name) / "sub" / "answers.jsonl"

    def test_key_changes_with_model_system_prompt_and_prompt(self):
        base = qa.cache_key("m", "s", "p")
        self.assertEqual(base, qa.cache_key("m", "s", "p"))
        self.assertEqual(base, hashlib.sha256("m\x1fs\x1fp".encode("utf-8")).hexdigest())
        self.assertEqual(len({base, qa.cache_key("m2", "s", "p"), qa.cache_key("m", "s2", "p"),
                              qa.cache_key("m", "s", "p2")}), 4)

    def test_round_trip_and_append_only(self):
        cache = qa.Cache(self.path, threading.Lock())
        self.assertIsNone(cache.get("k"))
        cache.put("k", {"key": "k", "result": "one"})
        cache.put("k", {"key": "k", "result": "two"})
        self.assertEqual(len(self.path.read_text().splitlines()), 2)
        self.assertEqual(cache.get("k")["result"], "two")
        self.assertEqual(qa.Cache(self.path, threading.Lock()).get("k")["result"], "two")

    def test_cache_survives_a_cut_off_last_line(self):
        cache = qa.Cache(self.path, threading.Lock())
        cache.put("a", {"key": "a", "result": "A"})
        cache.put("b", {"key": "b", "result": "B"})
        with open(self.path, "a", encoding="utf-8") as handle:
            handle.write('{"key": "c", "res')
        with mock.patch("sys.stderr"):
            reloaded = qa.Cache(self.path, threading.Lock())
        self.assertEqual(reloaded.get("a")["result"], "A")
        self.assertEqual(reloaded.get("b")["result"], "B")
        reloaded.put("c", {"key": "c", "result": "C"})
        self.assertEqual(reloaded.get("c")["result"], "C")
        self.assertEqual(qa.Cache(self.path, threading.Lock()).get("c")["result"], "C")


class FakeModels:
    """Records ``(model, prompt, kwargs)`` per call; ``respond(model, prompt)`` makes the text."""

    def __init__(self, respond, fail_at=None):
        self.respond = respond
        self.fail_at = fail_at
        self.calls = []
        self._lock = threading.Lock()

    def __call__(self, model, prompt, **kwargs):
        with self._lock:
            self.calls.append((model, prompt, kwargs))
            number = len(self.calls)
        if self.fail_at == number:
            raise llm.LlmError("synthetic failure")
        return llm.Reply(self.respond(model, prompt), {model: {"inputTokens": 1}}, {})

    def of(self, model):
        return [prompt for m, prompt, _ in self.calls if m == model]


def _good_marker_models(corpus):
    """Reader marks answers by whether the context holds a gold document; judge follows the mark."""
    by_text = {q.text: q for q in corpus.questions}

    def respond(model, prompt):
        if model == READER_MODEL:
            question = next(q for text, q in by_text.items() if f"Question: {text}\n" in prompt)
            has_gold = any(f"user: {corpus.documents[k].turns[0][1]}" in prompt for k in question.gold)
            return "MARK-GOOD" if has_gold else "MARK-BAD"
        return "yes" if "MARK-GOOD" in prompt else "no"

    return FakeModels(respond)


def _rankings(corpus):
    result = {}
    for q in corpus.questions:
        noise = sorted(k for k in corpus.documents if k not in q.gold)[:5]
        result[(q.id, "bm25")] = {"ranking": noise}
        result[(q.id, "cosine")] = {"ranking": noise}
        result[(q.id, "hivemem")] = {"ranking": sorted(q.gold)}
    return result


class RunQaTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = pathlib.Path(self._tmp.name)
        self.run_dir, self.cache_dir = root / "run", root / "cache"
        self.corpus = support.fixture_corpus()
        self.rankings = _rankings(self.corpus)

    def _run(self, models, **kwargs):
        kwargs.setdefault("out", lambda *_: None)
        return qa.run_qa(self.corpus, kwargs.pop("rankings", self.rankings), self.run_dir,
                         self.cache_dir, call=models, **kwargs)

    def _distinct_reader_prompts(self):
        prompts = set()
        for q in self.corpus.questions:
            for arm in qa.QA_ARMS:
                prompts.add(qa.reader_prompt(q, qa.context(q, arm, self.rankings, self.corpus)))
        return prompts

    def test_identical_contexts_call_the_reader_once(self):
        models = _good_marker_models(self.corpus)
        self._run(models)
        distinct = self._distinct_reader_prompts()
        self.assertLess(len(distinct), 16)
        self.assertEqual(len(models.of(READER_MODEL)), len(distinct))
        self.assertEqual(len(set(models.of(READER_MODEL))), len(distinct))
        lines = (self.run_dir / "qa.jsonl").read_text().splitlines()
        self.assertEqual(len(lines), 16)
        first = json.loads(lines[0])
        self.assertEqual((first["question_id"], first["arm"]), ("q_tea", "bm25"))
        self.assertEqual(set(first), {"question_id", "arm", "context", "answer", "verdict_raw", "correct"})

    def test_second_run_is_served_from_cache(self):
        self._run(_good_marker_models(self.corpus))
        again = _good_marker_models(self.corpus)
        self._run(again)
        self.assertEqual(again.calls, [])

    def test_no_cache_forces_fresh_calls(self):
        self._run(_good_marker_models(self.corpus))
        again = _good_marker_models(self.corpus)
        self._run(again, use_cache=False)
        self.assertEqual(len(again.of(READER_MODEL)), len(self._distinct_reader_prompts()))

    def test_changed_template_changes_every_key(self):
        self._run(_good_marker_models(self.corpus))
        again = _good_marker_models(self.corpus)
        with mock.patch.object(qa, "READER_TEMPLATE", qa.READER_TEMPLATE + " "):
            self._run(again)
        self.assertEqual(len(again.of(READER_MODEL)), len(self._distinct_reader_prompts()))

    def test_judge_uses_strict_validator(self):
        models = _good_marker_models(self.corpus)
        self._run(models)
        judge_calls = [kw for m, _, kw in models.calls if m == JUDGE_MODEL]
        self.assertTrue(judge_calls)
        for kwargs in judge_calls:
            self.assertTrue(kwargs["validate"]("Yes."))
            self.assertFalse(kwargs["validate"]("Yesterday the user ..."))

    def test_failed_call_stops_the_run(self):
        models = FakeModels(lambda model, prompt: "yes", fail_at=3)
        with self.assertRaises(llm.LlmError):
            self._run(models, workers=1)
        self.assertFalse((self.run_dir / "qa.json").exists())
        self.assertFalse((self.run_dir / "qa.jsonl").exists())

    def test_missing_ranking_stops_before_any_call(self):
        del self.rankings[("q_rain", "cosine")]
        models = _good_marker_models(self.corpus)
        with self.assertRaisesRegex(qa.QaError, "q_rain.*cosine"):
            self._run(models)
        self.assertEqual(models.calls, [])

    def test_summary_numbers(self):
        models = _good_marker_models(self.corpus)
        summary = self._run(models)
        self.assertEqual(summary["arms"]["hivemem"]["accuracy"], 1.0)
        self.assertEqual(summary["arms"]["oracle"]["accuracy"], 1.0)
        self.assertEqual(summary["arms"]["bm25"]["accuracy"], 0.0)
        self.assertEqual(summary["arms"]["cosine"]["n"], 4)
        self.assertEqual(summary["arms"]["hivemem"]["abstention"], {"n": 1, "accuracy": 1.0})
        self.assertEqual(summary["arms"]["hivemem"]["by_type"]["multi-session"],
                         {"n": 2, "accuracy": 1.0})
        self.assertEqual(summary["paired"]["bm25"]["b"], 4)
        self.assertEqual(summary["paired"]["bm25"]["c"], 0)
        self.assertEqual(summary["reader"], {"requested": READER_MODEL, "served": [READER_MODEL]})
        self.assertEqual(summary["judge"], {"requested": JUDGE_MODEL, "served": [JUDGE_MODEL]})
        self.assertEqual(json.loads((self.run_dir / "qa.json").read_text()), summary)

    def test_paired_only_for_answered_arms(self):
        summary = self._run(_good_marker_models(self.corpus), arms=("hivemem", "bm25"))
        self.assertEqual(set(summary["paired"]), {"bm25"})
        self.assertEqual(set(summary["arms"]), {"hivemem", "bm25"})


def _selfcheck_corpus():
    rows = support.fixture_rows()
    extra = dict(next(r for r in rows if r["question_id"] == "q_count"))
    extra.update(question_id="q_count2", answer=5, question="SYNTHETIC: how many items were sorted?")
    rows.append(extra)
    with tempfile.TemporaryDirectory() as tmp:
        path = pathlib.Path(tmp) / "rows.json"
        path.write_text(json.dumps(rows), encoding="utf-8")
        return dataset.load(str(path))


_JUDGE_FIELDS = re.compile(r"Correct Answer: (.*)\n\nModel Response: (.*)\n\nIs the model", re.S)


def _equality_judge(model, prompt):
    expected, given = _JUDGE_FIELDS.search(prompt).groups()
    return "yes" if expected == given else "no"


class SelfcheckTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.cache_dir = pathlib.Path(self._tmp.name)
        self.corpus = _selfcheck_corpus()

    def _run(self, models):
        return qa.run_selfcheck(self.corpus, self.cache_dir, call=models, out=lambda *_: None)

    def test_selfcheck_pairs_and_agreement(self):
        models = FakeModels(_equality_judge)
        result = self._run(models)
        self.assertEqual(result["agreement"], 1.0)
        self.assertEqual(result["disagreements"], [])
        self.assertEqual(result["agreed"], result["checks"])
        # four eligible questions: 4 positives + 2 negatives (the two multi-session ones pair up)
        self.assertEqual(result["checks"], 6)
        parrot = next(q for q in self.corpus.questions if q.id == "q_parrot_abs")
        for _, prompt, _ in models.calls:
            self.assertNotIn(parrot.text, prompt)
            self.assertNotIn(parrot.answer, prompt)

    def test_selfcheck_reports_disagreement(self):
        result = self._run(FakeModels(lambda model, prompt: "yes"))
        self.assertLess(result["agreement"], 1.0)
        self.assertTrue(result["disagreements"])
        for item in result["disagreements"]:
            self.assertEqual(set(item), {"question_id", "kind", "verdict_raw"})
            self.assertEqual(item["kind"], "negative")
            self.assertIn(item["question_id"], {"q_count", "q_count2"})


if __name__ == "__main__":
    unittest.main()
