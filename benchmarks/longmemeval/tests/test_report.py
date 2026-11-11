"""Tests for report.py: retrieval summary, the fixed headline rule and report rendering."""

import dataclasses
import json
import pathlib
import tempfile
import unittest
from unittest import mock

import support

import arms
import dataset
import report


def key(session_id):
    return support.key_of(session_id)


GOLD_FIRST = {
    "q_tea": [key("answer_tea_1"), key("answer_tea_2")],
    "q_count": [key("answer_count_5")],
    "q_rain": [key("answer_rain_4")],
}


def record(question_id, arm, ranking, **extra):
    base = {"question_id": question_id, "arm": arm, "ranking": list(ranking),
            "latency_ms": None, "hits_with_match": None, "recency_min": None, "recency_max": None}
    base.update(extra)
    return base


def rankings_for(arm_names, overrides=None, extra=None):
    """Gold-first rankings for every arm and non-abstention question; ``overrides`` replace some."""
    result = {}
    for arm in arm_names:
        for qid, ranking in GOLD_FIRST.items():
            result[(qid, arm)] = record(qid, arm, ranking)
    for (qid, arm), rec in (overrides or {}).items():
        result[(qid, arm)] = rec
    result.update(extra or {})
    return result


class SummaryTest(unittest.TestCase):
    def setUp(self):
        self.corpus = support.fixture_corpus()

    def summarize(self, **kwargs):
        return report.summarize_retrieval(self.corpus, rankings_for(("bm25",), **kwargs))

    def test_metrics_and_question_count(self):
        summary = self.summarize()
        self.assertEqual(summary["questions"], 3)
        self.assertEqual(summary["unreachable_recall_all@5"], 0)
        metrics = summary["arms"]["bm25"]["metrics"]
        self.assertEqual(metrics["recall_all@5"], 1.0)
        self.assertEqual(metrics["ndcg_any@5"], 1.0)

    def test_breakdowns(self):
        arm = self.summarize()["arms"]["bm25"]
        self.assertEqual({name: group["n"] for name, group in arm["by_type"].items()},
                         {"knowledge-update": 1, "multi-session": 1, "single-session-user": 1})
        self.assertEqual(arm["by_gold_size"]["2"]["n"], 1)
        self.assertEqual(arm["by_gold_size"]["1"]["n"], 2)
        self.assertEqual(arm["by_gold_length"]["<=8000"]["n"], 3)

    def test_diagnostics(self):
        rain = [key("noise_bike_3"), key("answer_count_5"), key("answer_rain_4")]
        summary = self.summarize(overrides={("q_rain", "bm25"): record("q_rain", "bm25", rain)})
        diagnostics = summary["arms"]["bm25"]["diagnostics"]
        # q_rain: bike is outside its haystack; count is gold of q_count only. Other questions: 0.
        self.assertAlmostEqual(diagnostics["top5_outside_haystack"], 1 / 3)
        self.assertAlmostEqual(diagnostics["top5_other_gold"], 1 / 3)
        self.assertEqual(diagnostics["top5_after_question_date"], 0.0)

    def test_after_question_date(self):
        tea = self.corpus.questions[0]
        self.assertEqual(tea.id, "q_tea")
        late = dict(tea.haystack_dates)
        late[key("answer_tea_2")] = "2023/07/01 (Sat) 00:00"
        questions = (dataclasses.replace(tea, haystack_dates=late),) + self.corpus.questions[1:]
        corpus = dataclasses.replace(self.corpus, questions=questions)
        summary = report.summarize_retrieval(corpus, rankings_for(("bm25",)))
        self.assertAlmostEqual(summary["arms"]["bm25"]["diagnostics"]["top5_after_question_date"], 1 / 3)

    def test_fewer_than_50(self):
        self.assertEqual(self.summarize()["arms"]["bm25"]["diagnostics"]["fewer_than_50"], 3)

    def test_match_share_and_recency(self):
        rank3 = lambda q: [key("answer_tea_1"), key("answer_tea_2"), key("noise_bike_3")] \
            if q == "q_tea" else [key("answer_count_5"), key("noise_twin_9"), key("noise_bike_3")]
        overrides = {
            ("q_tea", "hivemem"): record("q_tea", "hivemem", rank3("q_tea"), hits_with_match=2,
                                         recency_min=0.2, recency_max=0.5),
            ("q_count", "hivemem"): record("q_count", "hivemem", rank3("q_count"), hits_with_match=1,
                                           recency_min=0.1, recency_max=0.9),
        }
        summary = report.summarize_retrieval(
            self.corpus, rankings_for(("hivemem", "bm25"), overrides=overrides))
        hivemem = summary["arms"]["hivemem"]["diagnostics"]
        self.assertEqual(hivemem["match_share"], 0.5)
        self.assertEqual(hivemem["recency_min"], 0.1)
        self.assertEqual(hivemem["recency_max"], 0.9)
        bm25 = summary["arms"]["bm25"]["diagnostics"]
        self.assertIsNone(bm25["match_share"])
        self.assertIsNone(bm25["recency_min"])
        self.assertIsNone(bm25["recency_max"])

    def test_latency_only_from_hivemem(self):
        rankings = rankings_for(("hivemem", "bm25"), overrides={
            ("q_tea", "hivemem"): record("q_tea", "hivemem", GOLD_FIRST["q_tea"], latency_ms=10),
            ("q_count", "hivemem"): record("q_count", "hivemem", GOLD_FIRST["q_count"], latency_ms=20),
            ("q_rain", "hivemem"): record("q_rain", "hivemem", GOLD_FIRST["q_rain"], latency_ms=30),
            ("q_tea", "bm25"): record("q_tea", "bm25", GOLD_FIRST["q_tea"], latency_ms=9999),
        }, extra={("q_parrot_abs", "hivemem"): record("q_parrot_abs", "hivemem", [], latency_ms=40)})
        summary = report.summarize_retrieval(self.corpus, rankings)
        self.assertEqual(summary["latency_ms"], {"p50": 20, "p95": 40, "max": 40})

    def test_paired_against_every_other_arm(self):
        miss = record("q_rain", "cosine", [key("noise_bike_3")])
        summary = report.summarize_retrieval(
            self.corpus, rankings_for(("hivemem", "bm25", "cosine"),
                                      overrides={("q_rain", "cosine"): miss}))
        self.assertEqual((summary["paired"]["cosine"]["b"], summary["paired"]["cosine"]["c"]), (1, 0))
        self.assertEqual((summary["paired"]["bm25"]["b"], summary["paired"]["bm25"]["c"]), (0, 0))
        self.assertEqual(report.summarize_retrieval(self.corpus, rankings_for(("bm25",)))["paired"], {})

    def test_incomplete_arm_raises(self):
        rankings = rankings_for(("bm25", "cosine"))
        del rankings[("q_rain", "cosine")]
        with self.assertRaisesRegex(ValueError, "q_rain.*cosine"):
            report.summarize_retrieval(self.corpus, rankings)

    def test_empty_rankings_are_counted(self):
        summary = self.summarize(overrides={("q_rain", "bm25"): record("q_rain", "bm25", []),
                                            ("q_tea", "bm25"): record("q_tea", "bm25", [])})
        self.assertEqual(summary["arms"]["bm25"]["diagnostics"]["empty_rankings"], 2)
        self.assertEqual(self.summarize()["arms"]["bm25"]["diagnostics"]["empty_rankings"], 0)

    def test_abstention_ranking_is_ignored_for_metrics(self):
        plain = self.summarize()
        junk = {("q_parrot_abs", "bm25"): record("q_parrot_abs", "bm25", [key("noise_bike_3")])}
        with_junk = report.summarize_retrieval(
            self.corpus, rankings_for(("bm25",), extra=junk))
        self.assertEqual(plain, with_junk)


def _length_corpus():
    """One question per gold-length boundary plus questions with 3 and 4 gold documents."""
    lengths = (8000, 8001, 12000, 12001, 16000, 16001)
    documents, questions = {}, []

    def doc(key, length):
        documents[key] = dataset.Document(key, "S" * length, "2023/05/01 (Mon) 10:00", (key,), ())
        return key

    def question(qid, gold):
        questions.append(dataset.Question(qid, "multi-session", "SYNTHETIC: q?",
                                          "2023/06/01 (Thu) 10:00", "a", False, frozenset(gold),
                                          frozenset(gold), {}))

    for length in lengths:
        question(f"q_len_{length}", [doc(f"d_len_{length}", length)])
    question("q_three", [doc(f"d_three_{i}", 10) for i in range(3)])
    question("q_four", [doc(f"d_four_{i}", 10) for i in range(4)])
    return dataset.Corpus("0" * 64, None, tuple(questions), documents)


class BucketTest(unittest.TestCase):
    def setUp(self):
        self.corpus = _length_corpus()
        rankings = {(q.id, "bm25"): record(q.id, "bm25", sorted(q.gold)) for q in self.corpus.questions}
        # q_len_8001 and q_four miss: their groups must show it.
        rankings[("q_len_8001", "bm25")] = record("q_len_8001", "bm25", [])
        rankings[("q_four", "bm25")] = record("q_four", "bm25", sorted(self.corpus.questions[-1].gold)[:2])
        self.arm = report.summarize_retrieval(self.corpus, rankings)["arms"]["bm25"]

    def test_gold_length_boundaries(self):
        by_length = self.arm["by_gold_length"]
        self.assertEqual({name: group["n"] for name, group in by_length.items()},
                         {"<=8000": 3, "8001-12000": 2, "12001-16000": 2, ">16000": 1})
        self.assertEqual(by_length["8001-12000"]["recall_all@5"], 0.5)
        self.assertEqual(by_length[">16000"]["recall_all@5"], 1.0)

    def test_bucket_of_each_boundary_length(self):
        for length, bucket in ((8000, "<=8000"), (8001, "8001-12000"), (12000, "8001-12000"),
                               (12001, "12001-16000"), (16000, "12001-16000"), (16001, ">16000")):
            corpus = dataclasses.replace(self.corpus, questions=tuple(
                q for q in self.corpus.questions if q.id == f"q_len_{length}"))
            rankings = {(q.id, "bm25"): record(q.id, "bm25", sorted(q.gold)) for q in corpus.questions}
            groups = report.summarize_retrieval(corpus, rankings)["arms"]["bm25"]["by_gold_length"]
            self.assertEqual([name for name, group in groups.items() if group["n"]], [bucket], length)

    def test_three_or_more_gold_documents(self):
        by_size = self.arm["by_gold_size"]
        self.assertEqual({name: group["n"] for name, group in by_size.items()},
                         {"1": 6, "2": 0, ">=3": 2})
        self.assertEqual(by_size[">=3"]["recall_all@5"], 0.5)
        self.assertIsNone(by_size["2"]["recall_all@5"])


class HeadlineTest(unittest.TestCase):
    beaten = {"b": 30, "c": 10, "z": 3.16, "p": 0.002}
    tie = {"b": 12, "c": 10, "z": 0.43, "p": 0.83}
    worse = {"b": 5, "c": 20, "z": -3.0, "p": 0.004}

    def test_beat_both(self):
        self.assertEqual(report.headline({"bm25": self.beaten, "cosine": self.beaten}),
                         "HiveMem adds retrieval quality over its embedding model.")

    def test_beat_one(self):
        self.assertEqual(report.headline({"bm25": self.beaten, "cosine": self.tie}),
                         "No retrieval gain shown: hivemem does not beat cosine on recall_all@5.")

    def test_beat_none(self):
        self.assertEqual(report.headline({"bm25": self.tie, "cosine": self.worse}),
                         "No retrieval gain shown: hivemem does not beat bm25 and cosine on recall_all@5.")

    def test_significantly_worse_is_not_a_gain(self):
        text = report.headline({"bm25": self.beaten, "cosine": self.worse})
        self.assertIn("does not beat cosine", text)
        self.assertNotIn("bm25", text)

    def test_missing_control(self):
        self.assertTrue(report.headline({"bm25": self.beaten}).startswith("Headline not available"))
        self.assertTrue(report.headline({}).startswith("Headline not available"))


QA = {
    "reader": {"requested": "claude-haiku-5-5", "served": ["claude-haiku-5-5"]},
    "judge": {"requested": "claude-sonnet-5-5", "served": ["claude-sonnet-5-5"]},
    "arms": {
        "hivemem": {"n": 4, "accuracy": 0.75,
                    "by_type": {"multi-session": {"n": 2, "accuracy": 0.5}},
                    "abstention": {"n": 1, "accuracy": 1.0}},
        "bm25": {"n": 4, "accuracy": 0.5,
                 "by_type": {"multi-session": {"n": 2, "accuracy": 0.5}},
                 "abstention": {"n": 1, "accuracy": 0.0}},
    },
    "paired": {"bm25": {"b": 2, "c": 1, "z": 0.577, "p": 1.0}},
}
META = {"dataset_sha256": "0" * 64, "questions": 4, "documents": 6, "commit": "abc1234",
        "dirty": False, "ingest": {"sidecar_info": {"model": "synthetic-model"}},
        "retrieve_finished_at": "2026-01-01T10:00:00.000000Z",
        "qa_finished_at": "2026-01-01T11:00:00.000000Z", "qa_limit_questions": None}


class RenderTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.run_dir = pathlib.Path(self._tmp.name)
        corpus = support.fixture_corpus()
        self.summary = report.summarize_retrieval(corpus, rankings_for(("hivemem", "bm25", "cosine"), overrides={
            ("q_tea", "hivemem"): record("q_tea", "hivemem", GOLD_FIRST["q_tea"], latency_ms=12.0),
            ("q_tea", "bm25"): record("q_tea", "bm25", [key("noise_bike_3")], latency_ms=77.0),
        }))
        # The fixture has no question with more than 5 gold documents; the stored file says 3.
        self.summary["unreachable_recall_all@5"] = 3
        (self.run_dir / "meta.json").write_text(json.dumps(META), encoding="utf-8")
        (self.run_dir / "retrieval.json").write_text(json.dumps(self.summary), encoding="utf-8")
        (self.run_dir / "qa.json").write_text(json.dumps(QA), encoding="utf-8")

    def test_renders_from_stored_files_only(self):
        opened = []
        original = pathlib.Path.read_text

        def recording(path, *args, **kwargs):
            opened.append(path.name)
            return original(path, *args, **kwargs)

        with mock.patch.object(pathlib.Path, "read_text", recording), \
                mock.patch.object(dataset, "load", side_effect=AssertionError("dataset used")), \
                mock.patch.object(arms, "load_rankings", side_effect=AssertionError("rankings used")), \
                mock.patch("socket.socket", side_effect=AssertionError("socket used")):
            text = report.render(self.run_dir)
        self.assertEqual(sorted(opened), ["meta.json", "qa.json", "retrieval.json"])
        self.assertIn(self.summary["headline"], text)
        for name in ("hivemem", "bm25", "cosine"):
            self.assertIn(name, text)
        self.assertIn("recall_all@5", text)
        self.assertIn("cannot reach 1 for 3 questions", text)
        self.assertIn("synthetic-model", text)
        self.assertIn("Answer accuracy", text)

    def test_latency_appears_for_hivemem_only(self):
        text = report.render(self.run_dir)
        section = text.split("## Latency", 1)[1].split("\n## ", 1)[0]
        self.assertIn("hivemem", section)
        self.assertNotIn("bm25", section)
        self.assertNotIn("cosine", section)
        self.assertIn("12.000", section)
        self.assertNotIn("77.000", section)

    def test_renders_without_qa_json(self):
        (self.run_dir / "qa.json").unlink()
        text = report.render(self.run_dir)
        self.assertNotIn("Answer accuracy", text)
        self.assertIn(self.summary["headline"], text)

    def write_meta(self, **fields):
        (self.run_dir / "meta.json").write_text(json.dumps({**META, **fields}), encoding="utf-8")

    def write_qa(self, qa):
        (self.run_dir / "qa.json").write_text(json.dumps(qa), encoding="utf-8")

    def answer_section(self):
        return report.render(self.run_dir).split("## Answer accuracy", 1)[1]

    def test_empty_abstention_group_renders_a_dash(self):
        qa = json.loads(json.dumps(QA))
        for arm in qa["arms"].values():
            arm["abstention"] = {"n": 0, "accuracy": None}
        self.write_qa(qa)
        rows = [line for line in self.answer_section().splitlines() if line.startswith("| hivemem |")]
        self.assertEqual(rows, ["| hivemem | 4 | 0.750 | 0.500 | - |"])

    def test_stale_answer_stage_is_not_rendered(self):
        self.write_meta(qa_finished_at="2026-01-01T09:59:59.999999Z")
        section = self.answer_section()
        self.assertIn("Answer stage: stale — the rankings changed after it ran; run qa again.", section)
        self.assertNotIn("| hivemem", section)
        self.assertNotIn("Paired comparison", section)

    def test_answer_stage_after_the_rankings_is_rendered(self):
        self.assertIn("| hivemem", self.answer_section())

    def test_answer_stage_without_a_finish_time_is_not_rendered(self):
        meta = dict(META)
        del meta["qa_finished_at"]
        (self.run_dir / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
        section = self.answer_section()
        self.assertIn("run qa again", section)
        self.assertNotIn("| hivemem", section)

    def test_answer_section_states_the_graded_questions(self):
        self.write_qa({**QA, "questions": 4})
        self.assertIn("Questions graded: 4 of the run's 4.", self.answer_section())
        self.write_qa({**QA, "questions": 2})
        self.write_meta(qa_limit_questions=2)
        self.assertIn("Questions graded: 2 of the run's 4 (a stratified subset, "
                      "qa --limit-questions 2).", self.answer_section())

    def test_empty_rankings_column_is_rendered(self):
        self.assertIn("empty_rankings", report.render(self.run_dir))

    def test_write_creates_report_md(self):
        path = report.write(self.run_dir)
        self.assertEqual(path, self.run_dir / "report.md")
        self.assertEqual(path.read_text(encoding="utf-8"), report.render(self.run_dir))


if __name__ == "__main__":
    unittest.main()
