"""Tests for run.py: parser, small helpers, command wiring and an end-to-end run with fakes."""

import contextlib
import datetime
import io
import json
import pathlib
import re
import subprocess
import tempfile
import unittest
from unittest import mock

import support

import arms
import db
import ingest
import llm
import run

TOKEN = "synthetic-token"
REAL_PSQL = db.Psql  # run.db is the db module itself, so keep the class before patching


def invoke(argv):
    """Run ``run.main`` with captured streams; return (exit code, stdout, stderr)."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = run.main(argv)
    return code, out.getvalue(), err.getvalue()


class TempDirCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = self._tmp.name
        self.fixture = support.write_fixture(self.tmp)

    def token_file(self, text=TOKEN + "\n"):
        path = pathlib.Path(self.tmp) / "token"
        path.write_text(text, encoding="utf-8")
        return str(path)


class ParserTest(TempDirCase):
    def test_qa_without_run_id_is_a_usage_error_unless_selfcheck(self):
        code, _, err = invoke(["qa", "--work-dir", self.tmp])
        self.assertEqual(code, 2)
        self.assertIn("--run-id", err)

    def test_defaults(self):
        args = run.build_parser().parse_args(["qa", "--run-id", "r1"])
        self.assertEqual((args.reader_model, args.judge_model, args.workers),
                         ("claude-haiku-5-5", "claude-sonnet-5-5", 4))
        args = run.build_parser().parse_args(["retrieve"])
        self.assertEqual(args.hivemem_url, "http://127.0.0.1:18421")
        self.assertEqual(args.embedding_url, "http://127.0.0.1:18080")

    def test_report_requires_run_id(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            run.build_parser().parse_args(["report"])


class HelperTest(TempDirCase):
    def test_read_token_strips_and_rejects_empty(self):
        path = pathlib.Path(self.token_file("tok\n"))
        self.assertEqual(run.read_token(path), "tok")
        path.write_text("  \n", encoding="utf-8")
        missing = pathlib.Path(self.tmp) / "absent"
        for bad in (path, missing):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
                run.read_token(bad)
            self.assertEqual(raised.exception.code, 2)

    def test_read_token_message_contains_the_path(self):
        missing = pathlib.Path(self.tmp) / "absent"
        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit):
            run.read_token(missing)
        self.assertIn(str(missing), err.getvalue())

    def test_update_meta_merges(self):
        directory = pathlib.Path(self.tmp) / "run"
        run.update_meta(directory, a=1)
        result = run.update_meta(directory, b=2)
        self.assertEqual(json.loads((directory / "meta.json").read_text()), {"a": 1, "b": 2})
        self.assertEqual(result, {"a": 1, "b": 2})

    def test_new_run_id(self):
        moment = datetime.datetime(2026, 11, 10, 7, 8, 9, tzinfo=datetime.timezone.utc)
        self.assertEqual(run.new_run_id(moment), "20261110-070809")

    def test_git_state(self):
        def runner(command, **kwargs):
            out = "abc123\n" if "rev-parse" in command else " M file\n"
            return subprocess.CompletedProcess(command, 0, out, "")

        self.assertEqual(run.git_state(runner), {"commit": "abc123", "dirty": True})

        def broken(command, **kwargs):
            raise FileNotFoundError("git")

        self.assertEqual(run.git_state(broken), {"commit": None, "dirty": None})


class MainTest(TempDirCase):
    def test_corpus_command_prints_counts(self):
        code, out, err = invoke(["corpus", "--dataset", self.fixture, "--work-dir", self.tmp])
        self.assertEqual(code, 0)
        for part in ("questions=4", "non_abstention=3", "documents=6"):
            self.assertIn(part, out)
        self.assertIn("differs from the pinned dataset", err)

    def test_missing_dataset_is_a_usage_error_without_traceback(self):
        code, _, err = invoke(["corpus", "--dataset", self.tmp + "/nope.json",
                               "--work-dir", self.tmp])
        self.assertEqual(code, 2)
        self.assertIn("nope.json", err)
        self.assertIn("README", err)
        self.assertNotIn("Traceback", err)

    def test_retrieve_without_ingest_state_is_a_usage_error(self):
        code, _, err = invoke(["retrieve", "--dataset", self.fixture, "--work-dir", self.tmp,
                               "--token-file", self.token_file()])
        self.assertEqual(code, 2)
        self.assertIn("ingest", err)

    def test_ingest_with_stack_down_exits_1_with_one_error_line(self):
        code, _, err = invoke(["ingest", "--dataset", self.fixture, "--work-dir", self.tmp,
                               "--token-file", self.token_file(), "--psql", "false"])
        self.assertEqual(code, 1)
        self.assertTrue(err.startswith("error:"))
        self.assertEqual(len(err.strip().splitlines()), 1)
        self.assertNotIn("Traceback", err)

    def test_report_for_a_missing_run_is_a_usage_error(self):
        code, _, err = invoke(["report", "--run-id", "nope", "--work-dir", self.tmp])
        self.assertEqual(code, 2)
        self.assertIn("nope", err)

    def test_report_command_renders_stored_run(self):
        directory = pathlib.Path(self.tmp) / "runs" / "r1"
        directory.mkdir(parents=True)
        run.update_meta(directory, dataset_sha256="x", questions=3)
        import report
        corpus = support.fixture_corpus()
        rankings = {(q.id, arm): {"ranking": sorted(q.gold), "latency_ms": None}
                    for q in corpus.questions for arm in ("bm25", "hivemem")}
        (directory / "retrieval.json").write_text(
            json.dumps(report.summarize_retrieval(corpus, rankings)), encoding="utf-8")
        code, out, _ = invoke(["report", "--run-id", "r1", "--work-dir", self.tmp])
        self.assertEqual(code, 0)
        self.assertTrue((directory / "report.md").exists())
        self.assertIn("report.md", out)


def _equality_judge(model, prompt):
    expected, given = re.search(r"Correct Answer: (.*)\n\nModel Response: (.*)\n\nIs the model",
                                prompt, re.S).groups()
    return "yes" if expected == given else "no"


class SelfcheckCommandTest(TempDirCase):
    def setUp(self):
        super().setUp()
        # A second multi-session question gives the self-check negative pairs to judge.
        rows = support.fixture_rows()
        extra = dict(next(r for r in rows if r["question_id"] == "q_count"))
        extra.update(question_id="q_count2", answer=5, question="SYNTHETIC: how many were sorted?")
        self.fixture = str(pathlib.Path(self.tmp) / "selfcheck.json")
        pathlib.Path(self.fixture).write_text(json.dumps(rows + [extra]), encoding="utf-8")

    def selfcheck(self, respond):
        calls = []

        def call(model, prompt, **kwargs):
            calls.append(model)
            return llm.Reply(respond(model, prompt), {}, {})

        with mock.patch.object(run.llm, "call", call):
            return invoke(["qa", "--selfcheck", "--dataset", self.fixture,
                           "--work-dir", self.tmp, "--limit-questions", "1"])

    def test_agreement_above_threshold_exits_0(self):
        code, out, _ = self.selfcheck(_equality_judge)
        self.assertEqual(code, 0)
        self.assertIn("agreement", out)

    def test_agreement_below_threshold_exits_1_and_lists_disagreements(self):
        code, out, _ = self.selfcheck(lambda model, prompt: "yes")
        self.assertEqual(code, 1)
        self.assertIn("agreement", out)
        self.assertIn("negative", out)


class EndToEndTest(TempDirCase):
    def test_ingest_retrieve_qa_with_fakes(self):
        corpus = support.fixture_corpus()
        keys = sorted(corpus.documents)
        database = support.FakeBenchDb(token_hashes={ingest.token_hash(TOKEN)})

        def hits(arguments):
            return [{"topic": k, "score_semantic": 0.9 - 0.01 * i, "match": ["x"],
                     "score_total": 0.9 - 0.01 * i if "weight_semantic" in arguments else 0.5}
                    for i, k in enumerate(keys)]

        mcp = support.FakeMcp(database, hits=hits)

        def respond(model, prompt):
            return "yes" if model == "claude-sonnet-5-5" else "SYNTHETIC answer"

        def call(model, prompt, **kwargs):
            return llm.Reply(respond(model, prompt), {model: {"inputTokens": 1}}, {})

        base = ["--dataset", self.fixture, "--work-dir", self.tmp]
        stack = ["--token-file", self.token_file()]
        with mock.patch.object(run.hivemem, "McpClient", lambda *a, **k: mcp), \
                mock.patch.object(run.hivemem, "Sidecar", lambda *a, **k: support.FakeSidecar()), \
                mock.patch.object(run.db, "Psql",
                                  lambda command=None: REAL_PSQL(["psql"], runner=database)), \
                mock.patch.object(run.llm, "call", call), \
                mock.patch.object(run.llm, "cli_version", lambda: "9.9.9 (synthetic)"):
            self.assertEqual(invoke(["ingest", *base, *stack])[0], 0)
            code, out, err = invoke(["retrieve", "--run-id", "r1", *base, *stack])
            self.assertEqual((code, err), (0, ""))
            self.assertIn("r1", out)
            meta_after_first = json.loads(
                (pathlib.Path(self.tmp) / "runs" / "r1" / "meta.json").read_text())
            self.assertEqual(set(meta_after_first["plans"]), {"cosine", "chunkmax"})
            # A fully resumed retrieve takes no plans and must keep the recorded ones.
            self.assertEqual(invoke(["retrieve", "--run-id", "r1", *base, *stack])[0], 0)
            code, _, err = invoke(["qa", "--run-id", "r1", *base])
            self.assertEqual((code, err), (0, ""))

        directory = pathlib.Path(self.tmp) / "runs" / "r1"
        for name in ("meta.json", "rankings.jsonl", "retrieval.json", "qa.jsonl", "qa.json",
                     "report.md"):
            self.assertTrue((directory / name).exists(), name)
        meta = json.loads((directory / "meta.json").read_text())
        self.assertEqual(meta["plans"], meta_after_first["plans"])
        self.assertEqual(meta["started_at"], meta_after_first["started_at"])
        for field in ("ingest", "reader", "judge", "claude_version", "qa_finished_at",
                      "retrieve_finished_at", "dataset_sha256", "documents", "arms"):
            self.assertIn(field, meta)
        self.assertEqual(meta["judge"]["requested"], "claude-sonnet-5-5")
        self.assertEqual(meta["claude_version"], "9.9.9 (synthetic)")

    def test_unknown_arm_exits_1(self):
        corpus = support.fixture_corpus()
        database = support.FakeBenchDb(cells=[support.cell(k) for k in corpus.documents],
                                       token_hashes={ingest.token_hash(TOKEN)})
        state = {"dataset_sha256": corpus.sha256,
                 "sidecar_info": {"model": "synthetic-model", "max_chars": 50000}}
        (pathlib.Path(self.tmp) / "ingest.json").write_text(json.dumps(state))
        with mock.patch.object(run.hivemem, "McpClient", lambda *a, **k: support.FakeMcp()), \
                mock.patch.object(run.hivemem, "Sidecar", lambda *a, **k: support.FakeSidecar()), \
                mock.patch.object(run.db, "Psql",
                                  lambda command=None: REAL_PSQL(["psql"], runner=database)):
            code, _, err = invoke(["retrieve", "--dataset", self.fixture, "--work-dir", self.tmp,
                                   "--token-file", self.token_file(), "--arms", "nonsense"])
        self.assertEqual(code, 1)
        self.assertTrue(err.startswith("error:"))


if __name__ == "__main__":
    unittest.main()
