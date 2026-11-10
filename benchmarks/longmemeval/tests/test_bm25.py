"""Tests for bm25.py: tokenizer and BM25 ranker."""

import unittest

import support  # noqa: F401  (sys.path bootstrap)
import bm25


class Bm25Test(unittest.TestCase):
    DOCS = {"da": "apple banana apple", "db": "banana cherry", "dc": "cherry cherry date", "dd": "banana cherry"}

    def test_known_order_and_tie_break_by_key(self):
        index = bm25.Bm25(self.DOCS)
        self.assertEqual(index.rank("apple"), ["da"])
        self.assertEqual(index.rank("banana cherry")[:2], ["db", "dd"])   # identical documents -> key order
        self.assertEqual(index.rank("cherry", limit=1), ["dc"])

    def test_absent_term_and_case(self):
        index = bm25.Bm25(self.DOCS)
        self.assertEqual(index.rank("zzz"), [])
        self.assertEqual(index.rank("Apple, APPLE!"), ["da"])
        self.assertEqual(index.rank(""), [])

    def test_deterministic(self):
        self.assertEqual(bm25.Bm25(self.DOCS).rank("banana cherry"), bm25.Bm25(dict(reversed(self.DOCS.items()))).rank("banana cherry"))


if __name__ == "__main__":
    unittest.main()
