"""Tests for ingest.py: gates, write loop, settle, integrity and state file."""

import json
import pathlib
import tempfile
import unittest

import support
from support import cell

import db
import hivemem
import ingest

TOKEN = "synthetic-token"


def ready_db(**kw):
    return support.FakeBenchDb(token_hashes={ingest.token_hash(TOKEN)}, **kw)


def run(corpus, database, mcp=None, sidecar=None, **kw):
    mcp = mcp or support.FakeMcp(database)
    out = []
    state = ingest.run_ingest(corpus, mcp, db.Psql(["psql"], runner=database),
                              sidecar or support.FakeSidecar(), TOKEN,
                              pathlib.Path(kw.pop("tmp")) / "ingest.json",
                              sleep=lambda s: None, out=out.append, **kw)
    return state, mcp, out


def full_cells(corpus):
    return [cell(key) for key in sorted(corpus.documents)]


class TmpCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = self._tmp.name


class UnsettledQueryTest(TmpCase):
    def test_unsettled_sql_ignores_throttle_and_short_cells(self):
        self.assertNotIn("chunk_throttled_until", ingest.UNSETTLED_SQL)
        for part in ("status = 'committed'", "valid_until IS NULL", "length(content) > 2000",
                     "chunked_content_md5 IS DISTINCT FROM content_md5"):
            self.assertIn(part, ingest.UNSETTLED_SQL)

    def test_settle_waits_until_zero(self):
        database = ready_db(unsettled=(3, 1, 0))
        run(support.fixture_corpus(), database, tmp=self.tmp)
        self.assertGreaterEqual(database.scripts.count(ingest.UNSETTLED_SQL), 3)

    def test_settle_times_out(self):
        ticks = iter(range(0, 10**6, 600))
        with self.assertRaisesRegex(ingest.IngestError, "unsettled"):
            run(support.fixture_corpus(), ready_db(unsettled=(1,)), tmp=self.tmp,
                settle_timeout=1800, clock=lambda: next(ticks))


class GateTest(TmpCase):
    def check(self, database, pattern=None, sidecar=None, exc=ingest.IngestError):
        mcp = support.FakeMcp(database)
        with self.assertRaisesRegex(exc, pattern or ""):
            run(support.fixture_corpus(), database, mcp=mcp, sidecar=sidecar, tmp=self.tmp)
        self.assertEqual(mcp.calls, [])

    def test_unknown_token_aborts_before_any_write(self):
        self.check(support.FakeBenchDb(), "token")

    def test_foreign_realm_cell_aborts(self):
        self.check(ready_db(cells=[cell("personal-note", realm="personal")]), "realm")

    def test_foreign_topic_in_realm_aborts(self):
        self.check(ready_db(cells=[cell("dffffffffffffffff")]))

    def test_duplicate_topic_aborts(self):
        key = sorted(support.fixture_corpus().documents)[0]
        self.check(ready_db(cells=[cell(key), cell(key)]), "twice")

    def test_uncommitted_cell_aborts(self):
        key = sorted(support.fixture_corpus().documents)[0]
        self.check(ready_db(cells=[cell(key, status="pending")]))

    def test_sidecar_without_max_chars_aborts(self):
        self.check(ready_db(), "max_chars", sidecar=support.FakeSidecar(info={"model": "m"}))

    def test_stack_down_aborts_without_writing(self):
        runner = support.FakeRunner(lambda script: (1, "", 'service "db" is not running'))
        mcp = support.FakeMcp()
        with self.assertRaises(db.DbError):
            ingest.run_ingest(support.fixture_corpus(), mcp, db.Psql(["psql"], runner=runner),
                              support.FakeSidecar(), TOKEN,
                              pathlib.Path(self.tmp) / "ingest.json",
                              sleep=lambda s: None, out=lambda m: None)
        self.assertEqual(mcp.calls, [])


class WriteLoopTest(TmpCase):
    def test_sends_documents_in_key_order_without_label_leak(self):
        corpus = support.fixture_corpus()
        state, mcp, _ = run(corpus, ready_db(), tmp=self.tmp)
        topics = [c["topic"] for c in mcp.calls]
        self.assertEqual(topics, sorted(corpus.documents))
        self.assertEqual(len(topics), 6)
        for call in mcp.calls:
            self.assertEqual(set(call), {"content", "realm", "topic", "signal", "valid_from"})
            self.assertEqual((call["realm"], call["signal"]), ("longmemeval", "events"))
            for value in call.values():
                self.assertNotIn("answer_", value)
                for session_id in ("answer_tea_1", "answer_tea_2", "noise_twin_9", "noise_bike_3",
                                   "answer_rain_4", "answer_count_5", "answer_abs_6"):
                    self.assertNotIn(session_id, value)
        twin = next(c for c in mcp.calls if c["topic"] == support.key_of("answer_tea_1"))
        self.assertEqual(twin["valid_from"], "2023-04-01T07:00:00Z")

    def test_resume_skips_existing_topics(self):
        corpus = support.fixture_corpus()
        keys = sorted(corpus.documents)[:2]
        _, mcp, _ = run(corpus, ready_db(cells=[cell(k) for k in keys]), tmp=self.tmp)
        self.assertEqual(len(mcp.calls), 4)
        self.assertFalse({c["topic"] for c in mcp.calls} & set(keys))

    def test_retry_checks_database_first_and_does_not_resend(self):
        corpus = support.fixture_corpus()
        database = ready_db()
        first = sorted(corpus.documents)[0]
        fail = lambda i, kw: (hivemem.HiveMemError("timed out"), True) if i == 0 else None
        _, mcp, _ = run(corpus, database, mcp=support.FakeMcp(database, fail=fail), tmp=self.tmp)
        self.assertEqual(len(mcp.calls), 6)
        self.assertTrue(any(first in s and s.startswith("SELECT count(*) FROM cells WHERE realm")
                            for s in database.scripts))

    def test_three_resends_then_stop(self):
        corpus = support.fixture_corpus()
        database = ready_db()
        first = sorted(corpus.documents)[0]
        fail = lambda i, kw: hivemem.HiveMemError("embedding unavailable")
        mcp = support.FakeMcp(database, fail=fail)
        with self.assertRaisesRegex(ingest.IngestError, "embedding unavailable") as caught:
            run(corpus, database, mcp=mcp, tmp=self.tmp)
        self.assertIn(first, str(caught.exception))
        self.assertEqual(len(mcp.calls), 4)
        self.assertTrue(all(c["topic"] == first for c in mcp.calls))

    def test_first_write_must_be_visible_through_psql(self):
        mcp = support.FakeMcp(db=None)
        with self.assertRaisesRegex(ingest.IngestError, "did not arrive"):
            run(support.fixture_corpus(), ready_db(), mcp=mcp, tmp=self.tmp)
        self.assertEqual(len(mcp.calls), 1)


class IntegrityTest(unittest.TestCase):
    def check(self, cells, max_chars=50000, chunks=(0, 0), pattern=None):
        corpus = support.fixture_corpus()
        psql = db.Psql(["psql"], runner=ready_db(cells=cells, chunks=chunks))
        if pattern is None:
            return ingest.integrity(corpus, psql, max_chars)
        with self.assertRaisesRegex(ingest.IngestError, pattern) as caught:
            ingest.integrity(corpus, psql, max_chars)
        return str(caught.exception)

    def keys(self):
        return sorted(support.fixture_corpus().documents)

    def test_passes_and_reports_figures(self):
        result = self.check([cell(k) for k in self.keys()])
        self.assertEqual(result["cells"], result["distinct_topics"])
        self.assertEqual(result["distinct_topics"], result["expected"])
        self.assertEqual(result["expected"], 6)
        self.assertEqual(result["vectorless"], [])

    def test_fails_on_duplicate_topic(self):
        keys = self.keys()
        self.check([cell(k) for k in keys] + [cell(keys[0])], pattern="distinct|duplicate")

    def test_fails_on_missing_topic(self):
        self.check([cell(k) for k in self.keys()[1:]], pattern="cells|topic")

    def test_fails_on_extra_topic(self):
        self.check([cell(k) for k in self.keys()] + [cell("dffffffffffffffff")], pattern="topic")

    def test_fails_on_chunk_without_vector(self):
        self.check([cell(k) for k in self.keys()], chunks=(12, 1), pattern="chunk")

    def test_fails_on_unexpected_vectorless(self):
        cells = [cell(k) for k in self.keys()]
        cells[0]["vectorless"] = True
        self.check(cells, pattern="vector")

    def test_fails_when_long_document_has_a_vector(self):
        self.check([cell(k) for k in self.keys()], max_chars=10, pattern="vector")

    def test_vectorless_matches_long_documents(self):
        corpus = support.fixture_corpus()
        cells = [cell(k, vectorless=True) for k in sorted(corpus.documents)]
        result = self.check(cells, max_chars=10)
        self.assertEqual(result["vectorless"], result["expected_vectorless"])

    def test_expected_vectorless_uses_utf16_length(self):
        self.assertEqual(ingest.utf16_length("a\U0001F600"), 3)

    def test_reports_every_failed_check_at_once(self):
        keys = self.keys()
        message = self.check([cell(k) for k in keys] + [cell(keys[0])], chunks=(12, 1),
                             pattern="chunk")
        self.assertRegex(message, "distinct")


class StateTest(TmpCase):
    def test_state_file_content(self):
        corpus = support.fixture_corpus()
        sidecar = support.FakeSidecar()
        run(corpus, ready_db(), sidecar=sidecar, tmp=self.tmp)
        state = json.loads((pathlib.Path(self.tmp) / "ingest.json").read_text())
        self.assertEqual(state["dataset_sha256"], corpus.sha256)
        self.assertEqual(state["documents"], 6)
        self.assertEqual(state["sidecar_info"], sidecar.info())
        self.assertEqual(state["integrity"]["cells"], 6)
        self.assertIn("started_at", state)
        self.assertIn("finished_at", state)
        self.assertIn("limit_questions", state)


if __name__ == "__main__":
    unittest.main()
