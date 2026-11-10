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

    def test_abstention_ranking_is_ignored_for_metrics(self):
        plain = self.summarize()
        junk = {("q_parrot_abs", "bm25"): record("q_parrot_abs", "bm25", [key("noise_bike_3")])}
        with_junk = report.summarize_retrieval(
            self.corpus, rankings_for(("bm25",), extra=junk))
        self.assertEqual(plain, with_junk)


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
        "dirty": False, "ingest": {"sidecar_info": {"model": "synthetic-model"}}}


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

    def test_write_creates_report_md(self):
        path = report.write(self.run_dir)
        self.assertEqual(path, self.run_dir / "report.md")
        self.assertEqual(path.read_text(encoding="utf-8"), report.render(self.run_dir))


if __name__ == "__main__":
    unittest.main()
