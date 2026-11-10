"""Tests for jsonl.py: append-only files with crash-tail repair."""

import contextlib
import io
import pathlib
import tempfile
import unittest

import support  # noqa: F401

import jsonl


class JsonlTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = pathlib.Path(self._tmp.name)

    def test_missing_file_reads_empty(self):
        self.assertEqual(jsonl.read(self.tmp / "none.jsonl"), [])

    def test_append_then_read_round_trip(self):
        path = self.tmp / "sub" / "x.jsonl"
        jsonl.append(path, {"a": 1})
        jsonl.append(path, {"b": [2]})
        self.assertEqual(jsonl.read(path), [{"a": 1}, {"b": [2]}])
        self.assertTrue(path.read_text().endswith("\n"))

    def test_cut_off_last_line_is_dropped_and_removed(self):
        path = self.tmp / "x.jsonl"
        path.write_text('{"a": 1}\n{"a": 2}\n{"a": 1, "b', encoding="utf-8")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            records = jsonl.read(path)
        self.assertEqual(records, [{"a": 1}, {"a": 2}])
        self.assertNotEqual(err.getvalue(), "")
        self.assertEqual(path.read_text(), '{"a": 1}\n{"a": 2}\n')
        jsonl.append(path, {"a": 3})
        self.assertEqual(len(jsonl.read(path)), 3)

    def test_corrupt_middle_line_raises_with_line_number(self):
        path = self.tmp / "x.jsonl"
        path.write_text('{"a": 1}\ngarbage\n{"a": 2}\n', encoding="utf-8")
        with self.assertRaisesRegex(jsonl.JsonlError, r"x\.jsonl.*2"):
            jsonl.read(path)

    def test_blank_lines_are_ignored(self):
        path = self.tmp / "x.jsonl"
        path.write_text('{"a": 1}\n\n{"a": 2}\n\n', encoding="utf-8")
        self.assertEqual(jsonl.read(path), [{"a": 1}, {"a": 2}])


if __name__ == "__main__":
    unittest.main()
