"""Tests for metrics.py: retrieval metrics, paired test and percentile."""

import unittest

import support  # noqa: F401  (sys.path bootstrap)
import metrics


class MetricsTest(unittest.TestCase):
    def test_ndcg_literal_from_upstream_formula(self):
        # Upstream: dcg = rel[0] + sum(rel[i] / log2(i + 1) for i >= 1); ideal uses min(k, |gold|) ones.
        # Ranks 1..4 carry relevance 0,1,0,1 -> dcg = 1/log2(2) + 1/log2(4) = 1.5
        # ideal (3 gold, k=5)               -> 1 + 1/log2(2) + 1/log2(3) = 2.6309297535714578
        value = metrics.ndcg_any(["x", "g1", "y", "g2"], {"g1", "g2", "g3"}, 5)
        self.assertAlmostEqual(value, 0.5701406500739014, places=12)

    def test_gold_set_of_six_at_k5(self):
        gold = {f"g{i}" for i in range(6)}
        top = [f"g{i}" for i in range(5)]
        self.assertEqual(metrics.recall_all(top, gold, 5), 0.0)
        self.assertEqual(metrics.recall_any(top, gold, 5), 1.0)
        self.assertAlmostEqual(metrics.ndcg_any(top, gold, 5), 1.0, places=12)

    def test_empty_and_short_rankings(self):
        for fn in (metrics.recall_any, metrics.recall_all, metrics.ndcg_any):
            self.assertEqual(fn([], {"g"}, 5), 0.0)
            self.assertEqual(fn(["g"], {"g"}, 5), 1.0)
        self.assertEqual(metrics.recall_all(["g1"], {"g1", "g2"}, 5), 0.0)
        self.assertEqual(metrics.recall_any(["x", "g1"], {"g1"}, 1), 0.0)

    def test_paired_without_discordant_pairs(self):
        self.assertEqual(metrics.paired([True, False], [True, False]), metrics.Paired(0, 0, 0.0, 1.0))

    def test_paired_exact_binomial(self):
        first = [True] * 8 + [False] * 2 + [True] * 5
        second = [False] * 8 + [True] * 2 + [True] * 5
        result = metrics.paired(first, second)
        self.assertEqual((result.b, result.c), (8, 2))
        self.assertAlmostEqual(result.z, 1.8973665961010275, places=12)
        self.assertEqual(result.p, 0.109375)            # 2 * (C(10,0) + C(10,1) + C(10,2)) / 2**10

    def test_paired_rejects_unequal_lengths(self):
        with self.assertRaises(ValueError):
            metrics.paired([True], [True, False])

    def test_percentile_nearest_rank(self):
        self.assertEqual(metrics.percentile([40, 10, 30, 20], 50), 20)
        self.assertEqual(metrics.percentile([40, 10, 30, 20], 95), 40)
        self.assertIsNone(metrics.percentile([], 50))


if __name__ == "__main__":
    unittest.main()
