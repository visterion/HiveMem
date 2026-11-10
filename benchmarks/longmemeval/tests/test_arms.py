"""Tests for arms.py: SQL scripts, plan check, reply validation and the retrieve run."""

import contextlib
import io
import pathlib
import tempfile
import unittest

import support

import arms
import db
import ingest

TOKEN = "synthetic-token"


def hit(topic, sem, total, **extra):
    return {"topic": topic, "score_semantic": sem, "score_total": total, **extra}


class SqlTest(unittest.TestCase):
    def test_script_is_one_transaction_in_order(self):
        for script in (arms.cosine_script("[1.0]"), arms.chunkmax_script("[1.0]")):
            positions = [script.index(p) for p in (
                "BEGIN;", "SET LOCAL enable_indexscan = off;", "SET LOCAL enable_bitmapscan = off;",
                "SELECT c.topic", "COMMIT;")]
            self.assertEqual(positions, sorted(positions))
            for part in ("c.realm = 'longmemeval'", "(c.valid_until IS NULL OR c.valid_until > now())",
                         "c.status = 'committed'", "ORDER BY sem DESC, c.id", "LIMIT 50",
                         "'[1.0]'::vector"):
                self.assertIn(part, script)

    def test_explain_runs_inside_the_same_block(self):
        script = arms.cosine_script("[1.0]", explain=True)
        self.assertLess(script.index("SET LOCAL enable_bitmapscan = off;"), script.index("EXPLAIN SELECT"))
        self.assertLess(script.index("EXPLAIN SELECT"), script.index("COMMIT;"))

    def test_chunkmax_definition(self):
        self.assertIn("GREATEST(COALESCE(max(1 - (ch.embedding <=> q.v)), 0), 1 - (c.embedding <=> q.v))",
                      arms.chunkmax_script("[1.0]"))

    def test_check_plan(self):
        arms.check_plan("Limit\n  ->  Sort\n    ->  Seq Scan on cells c")
        with self.assertRaises(arms.ArmError):
            arms.check_plan("Index Scan using cells_embedding_hnsw on cells")
        with self.assertRaises(arms.ArmError):
            arms.check_plan("Bitmap Index Scan on idx")

    def test_vector_literal(self):
        self.assertEqual(arms.vector_literal([0.5, -1, 2e-05]), "[0.5,-1.0,2e-05]")


class ValidationTest(unittest.TestCase):
    def test_empty_reply_is_valid(self):
        arms.validate_hits("hivemem", [])

    def test_keyword_only_fallback_is_rejected(self):
        with self.assertRaises(arms.ArmError):
            arms.validate_hits("hivemem", [hit("da", 0.0, 0.29), hit("db", 0.0, 0.1)])

    def test_missing_score_semantic_is_rejected(self):
        with self.assertRaises(arms.ArmError):
            arms.validate_hits("hivemem", [{"topic": "da", "score_total": 0.4}])

    def test_semantic_weights_must_be_honoured(self):
        with self.assertRaises(arms.ArmError):
            arms.validate_hits("hivemem-semantic", [hit("da", 0.5046, 0.5402)])
        arms.validate_hits("hivemem-semantic", [hit("da", 0.5046, 0.5046), hit("db", 0.3, 0.30005)])

    def test_search_arguments(self):
        base = {"query": "q?", "limit": 50, "where": {"realm": "longmemeval"}, "include": ["scores"]}
        self.assertEqual(arms.search_arguments("hivemem", "q?"), base)
        weights = {"weight_semantic": 1, "weight_keyword": 0, "weight_recency": 0,
                   "weight_importance": 0, "weight_popularity": 0, "weight_graph_proximity": 0}
        self.assertEqual(arms.search_arguments("hivemem-semantic", "q?"), {**base, **weights})

    def test_to_ranking_rejects_unknown_and_duplicate(self):
        self.assertEqual(arms.to_ranking(["a", "b"], {"a", "b", "c"}), ["a", "b"])
        with self.assertRaises(arms.ArmError):
            arms.to_ranking(["a", "x"], {"a", "b"})
        with self.assertRaises(arms.ArmError):
            arms.to_ranking(["a", "a"], {"a", "b"})


class FlippingSidecar(support.FakeSidecar):
    """Reports a different ``/info`` from the second call on."""

    def __init__(self):
        super().__init__()
        self.count = 0

    def info(self):
        self.count += 1
        info = super().info()
        if self.count > 1:
            info["max_chars"] = 1
        return info


class RunTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.run_dir = pathlib.Path(self._tmp.name)
        self.corpus = support.fixture_corpus()
        self.keys = sorted(self.corpus.documents)
        self.state = {"dataset_sha256": self.corpus.sha256,
                      "sidecar_info": {"model": "synthetic-model", "max_chars": 50000}}

    def database(self, **kw):
        return support.FakeBenchDb(cells=[support.cell(k) for k in self.keys],
                                   token_hashes={ingest.token_hash(TOKEN)}, **kw)

    def mcp(self, hits=None):
        def default(arguments):
            return [hit(k, 0.9 - 0.01 * i, 0.9 - 0.01 * i if "weight_semantic" in arguments
                        else 0.5, match=["x"]) for i, k in enumerate(self.keys)]
        return support.FakeMcp(hits=hits or default)

    def retrieve(self, database=None, mcp=None, sidecar=None, state=None):
        database = database or self.database()
        mcp = mcp or self.mcp()
        result = arms.run_retrieve(self.corpus, mcp, db.Psql(["psql"], runner=database),
                                   sidecar or support.FakeSidecar(), TOKEN, state or self.state,
                                   self.run_dir, clock=iter(range(0, 10**6, 2)).__next__,
                                   out=lambda message: None)
        return result, mcp

    def records(self):
        return arms.load_rankings(self.run_dir / "rankings.jsonl")

    def test_all_questions_and_arms_are_ranked(self):
        result, _ = self.retrieve()
        records = self.records()
        self.assertEqual(len(records), 20)
        self.assertIn(("q_parrot_abs", "bm25"), records)
        for record in records.values():
            self.assertEqual(len(set(record["ranking"])), len(record["ranking"]))
            self.assertTrue(set(record["ranking"]) <= set(self.keys))
        self.assertEqual(set(result["plans"]), {"cosine", "chunkmax"})
        self.assertEqual(result["sidecar_info"], self.state["sidecar_info"])
        self.assertEqual(set(result["arms"]), set(arms.ARMS))

    def test_latency_only_for_hivemem_and_order(self):
        _, mcp = self.retrieve()
        for (qid, arm), record in self.records().items():
            if arm == "hivemem":
                self.assertIsInstance(record["latency_ms"], float)
                self.assertEqual(record["hits_with_match"], len(self.keys))
                self.assertIsNone(record["recency_min"])
            else:
                self.assertIsNone(record["latency_ms"])
        flags = ["weight_semantic" in call for call in mcp.calls]
        self.assertEqual(flags, [False, True] * 4)

    def test_plans_are_checked_before_first_query(self):
        with self.assertRaises(arms.ArmError):
            self.retrieve(database=self.database(plan="Index Scan using x"))
        self.assertEqual(self.records(), {})
        self.assertFalse((self.run_dir / "rankings.jsonl").exists())

    def test_plans_are_checked_before_first_search(self):
        mcp = self.mcp()
        with self.assertRaises(arms.ArmError):
            self.retrieve(database=self.database(plan="Index Scan using x"), mcp=mcp)
        self.assertEqual(mcp.calls, [])

    def test_degraded_reply_stops_and_writes_nothing_for_that_pair(self):
        mcp = self.mcp(hits=lambda arguments: [hit(self.keys[0], 0.0, 0.3)])
        with self.assertRaises(arms.ArmError):
            self.retrieve(mcp=mcp)
        self.assertFalse([k for k in self.records() if k[1] == "hivemem"])

    def test_unknown_topic_stops_the_run(self):
        mcp = self.mcp(hits=lambda arguments: [hit("not-a-key", 0.5, 0.5)])
        with self.assertRaises(arms.ArmError):
            self.retrieve(mcp=mcp)

    def test_duplicate_document_stops_the_run(self):
        mcp = self.mcp(hits=lambda arguments: [hit(self.keys[0], 0.5, 0.5)] * 2)
        with self.assertRaises(arms.ArmError):
            self.retrieve(mcp=mcp)

    def test_changed_model_stops_before_first_query(self):
        mcp = self.mcp()
        with self.assertRaises(arms.ArmError):
            self.retrieve(mcp=mcp, sidecar=support.FakeSidecar(info={"model": "other", "max_chars": 50000}))
        self.assertEqual(mcp.calls, [])
        self.assertFalse((self.run_dir / "rankings.jsonl").exists())

    def test_info_changed_during_run_stops(self):
        with self.assertRaises(arms.ArmError):
            self.retrieve(sidecar=FlippingSidecar())

    def test_dataset_mismatch_stops(self):
        with self.assertRaises(arms.ArmError):
            self.retrieve(state={**self.state, "dataset_sha256": "0" * 64})

    def test_resume_skips_existing_pairs(self):
        self.retrieve()
        mcp = self.mcp()

        class Counting(support.FakeSidecar):
            embeds = 0

            def embed_query(self, text):
                Counting.embeds += 1
                return super().embed_query(text)

        self.retrieve(mcp=mcp, sidecar=Counting())
        self.assertEqual(mcp.calls, [])
        self.assertEqual(Counting.embeds, 0)
        self.assertEqual(len(self.records()), 20)

    def test_resume_after_cut_off_write(self):
        self.retrieve()
        path = self.run_dir / "rankings.jsonl"
        lines = path.read_text().splitlines()[:7]
        path.write_text("\n".join(lines) + '\n{"question_id": "q_te', encoding="utf-8")
        with contextlib.redirect_stderr(io.StringIO()):
            self.retrieve()
        self.assertEqual(len(self.records()), 20)


if __name__ == "__main__":
    unittest.main()
