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

    def test_agreement_line_is_printed_once(self):
        _, out, _ = self.selfcheck(_equality_judge)
        self.assertEqual(len([line for line in out.splitlines() if "self-check" in line]), 1)

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
        # Adapted (M3): retrieve needs a finished ingest state, else it exits 2 first.
        state = {"dataset_sha256": corpus.sha256, "limit_questions": None,
                 "started_at": "2026-01-01T00:00:00Z", "finished_at": "2026-01-01T01:00:00Z",
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


class StreamTest(TempDirCase):
    def test_streams_are_line_buffered_before_the_command_runs(self):
        events = []

        class Stream(io.StringIO):
            def __init__(self, name):
                super().__init__()
                self.name = name

            def reconfigure(self, **kwargs):
                events.append((self.name, kwargs))

            def write(self, text):
                events.append((self.name, "write"))
                return super().write(text)

        with mock.patch.object(run.sys, "stdout", Stream("out")), \
                mock.patch.object(run.sys, "stderr", Stream("err")):
            self.assertEqual(run.main(["corpus", "--dataset", self.fixture, "--work-dir", self.tmp]), 0)
        self.assertEqual(events[:2], [("out", {"line_buffering": True}),
                                      ("err", {"line_buffering": True})])
        self.assertIn(("out", "write"), events[2:])


def _hits(keys):
    def hits(arguments):
        return [{"topic": k, "score_semantic": 0.9 - 0.01 * i, "match": ["x"],
                 "score_total": 0.9 - 0.01 * i if "weight_semantic" in arguments else 0.5}
                for i, k in enumerate(keys)]
    return hits


class StackCase(TempDirCase):
    """Runs the commands against the shared fakes; ``self.info`` is the sidecar's ``/info``."""

    def setUp(self):
        super().setUp()
        self.corpus = support.fixture_corpus()
        self.database = support.FakeBenchDb(token_hashes={ingest.token_hash(TOKEN)})
        self.mcp = support.FakeMcp(self.database, hits=_hits(sorted(self.corpus.documents)))
        self.info = {"model": support.SIDECAR_MODEL, "max_chars": 50000,
                     "query_prefix": "", "intra_op_threads": 8}
        self.sidecar_factory = lambda: support.FakeSidecar(info=dict(self.info))
        self.model_calls = []
        self.versions = []
        self.base = ["--dataset", self.fixture, "--work-dir", self.tmp]
        self.stack = ["--token-file", self.token_file()]
        self.run_dir = pathlib.Path(self.tmp) / "runs" / "r1"

    def call(self, model, prompt, **kwargs):
        self.model_calls.append(model)
        text = "yes" if model == "claude-sonnet-5-5" else "SYNTHETIC answer"
        return llm.Reply(text, {model: {"inputTokens": 1}}, {})

    def version(self):
        self.versions.append(1)
        return "9.9.9 (synthetic)"

    def invoke(self, *argv):
        with mock.patch.object(run.hivemem, "McpClient", lambda *a, **k: self.mcp), \
                mock.patch.object(run.hivemem, "Sidecar", lambda *a, **k: self.sidecar_factory()), \
                mock.patch.object(run.db, "Psql",
                                  lambda command=None: REAL_PSQL(["psql"], runner=self.database)), \
                mock.patch.object(run.llm, "call", self.call), \
                mock.patch.object(run.llm, "cli_version", self.version):
            return invoke(list(argv))

    def ingest(self, *extra):
        code, _, err = self.invoke("ingest", *self.base, *self.stack, *extra)
        self.assertEqual((code, err), (0, ""))

    def retrieve(self, *extra):
        return self.invoke("retrieve", "--run-id", "r1", *self.base, *self.stack, *extra)

    def qa(self, *extra):
        return self.invoke("qa", "--run-id", "r1", *self.base, *extra)

    def meta(self):
        return json.loads((self.run_dir / "meta.json").read_text())

    def rankings_bytes(self):
        return (self.run_dir / "rankings.jsonl").read_bytes()

    def assert_one_error_line(self, err, *parts):
        self.assertTrue(err.startswith("error:"), err)
        self.assertEqual(len(err.strip().splitlines()), 1, err)
        self.assertNotIn("Traceback", err)
        for part in parts:
            self.assertIn(part, err)


class FingerprintTest(StackCase):
    def setUp(self):
        super().setUp()
        self.ingest()
        code, _, err = self.retrieve()
        self.assertEqual((code, err), (0, ""))
        self.before = self.rankings_bytes()

    def assert_refused(self, *argv, keys):
        scripts, calls = len(self.database.scripts), len(self.mcp.calls)
        meta = self.meta()
        code, _, err = self.retrieve(*argv)
        self.assertEqual(code, 1)
        self.assert_one_error_line(err, "run r1 was started under different conditions",
                                   f"({keys})", "use a new --run-id")
        self.assertEqual(len(self.database.scripts), scripts)
        self.assertEqual(len(self.mcp.calls), calls)
        self.assertEqual(self.rankings_bytes(), self.before)
        self.assertEqual(self.meta(), meta)

    def test_fingerprint_is_recorded(self):
        meta = self.meta()
        state = json.loads((pathlib.Path(self.tmp) / "ingest.json").read_text())
        self.assertEqual(meta["fingerprint"], {
            "dataset_sha256": self.corpus.sha256, "limit_questions": None,
            "ingest_started_at": state["started_at"], "ingest_finished_at": state["finished_at"],
            "sidecar_info": {"model": support.SIDECAR_MODEL, "max_chars": 50000, "query_prefix": ""}})
        self.assertEqual(meta["retrieve_sidecar_info"], self.info)

    def test_resume_with_the_same_conditions_works(self):
        code, _, err = self.retrieve()
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(self.rankings_bytes(), self.before)

    def test_changed_limit_questions_is_refused(self):
        self.assert_refused("--limit-questions", "2", keys="limit_questions")

    def test_changed_ingest_finish_is_refused(self):
        path = pathlib.Path(self.tmp) / "ingest.json"
        state = json.loads(path.read_text())
        state["finished_at"] = "2099-01-01T00:00:00Z"
        path.write_text(json.dumps(state))
        self.assert_refused(keys="ingest_finished_at")

    def test_changed_query_prefix_is_refused(self):
        self.info["query_prefix"] = "query: "
        self.assert_refused(keys="sidecar_info")

    def test_changed_intra_op_threads_alone_is_accepted(self):
        self.info["intra_op_threads"] = 2
        code, _, err = self.retrieve()
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(self.meta()["retrieve_sidecar_info"]["intra_op_threads"], 2)

    def test_run_without_a_fingerprint_is_refused(self):
        meta = self.meta()
        del meta["fingerprint"]
        (self.run_dir / "meta.json").write_text(json.dumps(meta))
        code, _, err = self.retrieve()
        self.assertEqual(code, 1)
        self.assert_one_error_line(err, "no recorded fingerprint", "use a new --run-id")
        self.assertEqual(self.rankings_bytes(), self.before)

    def test_invocations_grow_by_one_per_command(self):
        first = self.meta()
        self.assertEqual([entry["command"] for entry in first["invocations"]], ["retrieve"])
        self.assertEqual(self.retrieve()[0], 0)
        self.assertEqual(self.qa()[0], 0)
        meta = self.meta()
        self.assertEqual([entry["command"] for entry in meta["invocations"]],
                         ["retrieve", "retrieve", "qa"])
        for entry in meta["invocations"]:
            self.assertEqual(set(entry), {"command", "at", "commit", "dirty"})
        self.assertEqual((meta["commit"], meta["dirty"]), (first["commit"], first["dirty"]))
        self.assertNotIn("commit", meta["fingerprint"])

    def test_info_changing_during_the_run_invalidates_the_rankings(self):
        (self.run_dir / "rankings.jsonl").unlink()
        reads = []

        def flipping():
            sidecar = support.FakeSidecar(info=dict(self.info))
            original = sidecar.info

            def info():
                reads.append(1)
                result = original()
                if len(reads) >= 3:  # fingerprint, gate, end of run
                    result["max_chars"] = 1
                return result

            sidecar.info = info
            return sidecar

        self.sidecar_factory = flipping
        code, _, err = self.retrieve()
        self.assertEqual(code, 1)
        self.assert_one_error_line(err, "rankings.invalid.jsonl")
        self.assertFalse((self.run_dir / "rankings.jsonl").exists())
        self.assertTrue((self.run_dir / "rankings.invalid.jsonl").exists())


class RetrieveCommandTest(StackCase):
    def write_state(self, **fields):
        path = pathlib.Path(self.tmp) / "ingest.json"
        state = {"dataset_sha256": self.corpus.sha256, "limit_questions": None,
                 "started_at": "2026-01-01T00:00:00Z", "finished_at": None,
                 "sidecar_info": dict(self.info), "integrity": None}
        state.update(fields)
        path.write_text(json.dumps(state))

    def test_unfinished_ingest_exits_2(self):
        self.write_state()
        code, _, err = self.retrieve()
        self.assertEqual(code, 2)
        self.assert_one_error_line(err, "ingest has not finished")
        self.assertEqual(self.database.scripts, [])

    def test_corrupt_ingest_state_exits_2(self):
        (pathlib.Path(self.tmp) / "ingest.json").write_text("{cut")
        code, _, err = self.retrieve()
        self.assertEqual(code, 2)
        self.assert_one_error_line(err, "ingest.json")

    def test_summary_error_is_one_error_line(self):
        self.ingest()
        with mock.patch.object(run.report, "summarize_retrieval",
                               side_effect=ValueError("synthetic summary failure")):
            code, _, err = self.retrieve()
        self.assertEqual(code, 1)
        self.assert_one_error_line(err, "synthetic summary failure")

    def test_retrieval_json_covers_every_complete_arm_of_the_run(self):
        self.ingest()
        self.assertEqual(self.retrieve("--arms", "bm25")[0], 0)
        self.assertEqual(self.retrieve("--arms", "cosine")[0], 0)
        summary = json.loads((self.run_dir / "retrieval.json").read_text())
        self.assertEqual(list(summary["arms"]), ["bm25", "cosine"])
        self.assertEqual(list(self.meta()["arms"]), ["bm25", "cosine"])

    def test_partly_ranked_arm_is_left_out_and_named(self):
        self.ingest()
        self.assertEqual(self.retrieve("--arms", "bm25")[0], 0)
        record = {"question_id": "q_tea", "arm": "chunkmax", "ranking": [], "latency_ms": None,
                  "hits_with_match": None, "recency_min": None, "recency_max": None}
        with open(self.run_dir / "rankings.jsonl", "a", encoding="utf-8") as handle:
            handle.write(json.dumps(record) + "\n")
        code, _, err = self.retrieve("--arms", "bm25")
        self.assertEqual(code, 0)
        self.assertIn("chunkmax", err)
        summary = json.loads((self.run_dir / "retrieval.json").read_text())
        self.assertEqual(list(summary["arms"]), ["bm25"])
        self.assertEqual(list(self.meta()["arms"]), ["bm25"])

    def test_plans_of_separate_invocations_are_merged(self):
        self.ingest()
        self.assertEqual(self.retrieve("--arms", "cosine")[0], 0)
        self.assertEqual(self.retrieve("--arms", "chunkmax")[0], 0)
        self.assertEqual(set(self.meta()["plans"]), {"cosine", "chunkmax"})


class QaCommandTest(StackCase):
    def setUp(self):
        super().setUp()
        self.ingest()
        code, _, err = self.retrieve()
        self.assertEqual((code, err), (0, ""))

    def test_subset_of_a_full_run_grades_with_the_run_documents(self):
        code, out, err = self.qa("--limit-questions", "2")
        self.assertEqual((code, err), (0, ""), out)
        lines = [json.loads(line) for line in (self.run_dir / "qa.jsonl").read_text().splitlines()]
        self.assertEqual({line["question_id"] for line in lines}, {"q_tea", "q_count"})
        haystacks = {q.id: q.haystack for q in self.corpus.questions}
        self.assertTrue(any(set(line["context"]) - haystacks[line["question_id"]]
                            for line in lines if line["arm"] != "oracle"))
        meta = self.meta()
        self.assertEqual(meta["qa_limit_questions"], 2)
        self.assertEqual(meta["qa_arms"], ["bm25", "cosine", "hivemem", "oracle"])
        report_text = (self.run_dir / "report.md").read_text()
        self.assertIn("Questions graded: 2 of the run's 4 (a stratified subset, "
                      "qa --limit-questions 2).", report_text)

    def test_full_qa_is_not_called_a_subset(self):
        self.assertEqual(self.qa()[0], 0)
        self.assertIsNone(self.meta()["qa_limit_questions"])
        self.assertIn("Questions graded: 4 of the run's 4.",
                      (self.run_dir / "report.md").read_text())

    def test_other_dataset_exits_1_without_model_calls(self):
        other = pathlib.Path(self.tmp) / "other.json"
        other.write_text(pathlib.Path(self.fixture).read_text() + "\n")
        code, _, err = self.invoke("qa", "--run-id", "r1", "--dataset", str(other),
                                   "--work-dir", self.tmp)
        self.assertEqual(code, 1)
        self.assert_one_error_line(err, "dataset")
        self.assertEqual((self.model_calls, self.versions), ([], []))

    def test_run_without_meta_exits_2(self):
        (pathlib.Path(self.tmp) / "runs" / "r9").mkdir()
        code, _, err = self.invoke("qa", "--run-id", "r9", *self.base)
        self.assertEqual(code, 2)
        self.assert_one_error_line(err, "run retrieve first")
        self.assertEqual(self.model_calls, [])

    def test_cli_version_is_read_before_any_model_call(self):
        def failing():
            raise llm.LlmError("synthetic --version failure")

        self.version = failing
        code, _, err = self.qa()
        self.assertEqual(code, 1)
        self.assertEqual(self.model_calls, [])
        self.assertFalse((self.run_dir / "qa.json").exists())


if __name__ == "__main__":
    unittest.main()
