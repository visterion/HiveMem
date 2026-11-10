"""Retrieval metrics (upstream LongMemEval formulas), paired exact test, percentile."""

import math
from collections.abc import Collection, Sequence
from typing import NamedTuple

KS = (1, 3, 5, 10, 30, 50)


def recall_any(ranking: Sequence[str], gold: Collection[str], k: int) -> float:
    """1.0 when at least one gold key is in the top k, else 0.0."""
    return 1.0 if any(key in gold for key in ranking[:k]) else 0.0


def recall_all(ranking: Sequence[str], gold: Collection[str], k: int) -> float:
    """1.0 when every gold key is in the top k, else 0.0."""
    top = set(ranking[:k])
    return 1.0 if all(key in top for key in gold) else 0.0


def _dcg(relevances: Sequence[float]) -> float:
    if not relevances:
        return 0.0
    return relevances[0] + sum(rel / math.log2(i + 1) for i, rel in enumerate(relevances) if i >= 1)


def ndcg_any(ranking: Sequence[str], gold: Collection[str], k: int) -> float:
    """Binary-relevance NDCG@k with the upstream DCG (rank 1 and 2 both discounted by 1)."""
    relevances = [1.0 if key in gold else 0.0 for key in ranking[:k]]
    ideal = _dcg([1.0] * min(k, len(gold)))
    if ideal == 0.0:
        return 0.0
    return _dcg(relevances) / ideal


class Paired(NamedTuple):
    """Discordant counts, z statistic and exact two-sided binomial p-value."""

    b: int
    c: int
    z: float
    p: float


def paired(first: Sequence[bool], second: Sequence[bool]) -> Paired:
    """Paired comparison; b = only first true, c = only second true."""
    if len(first) != len(second):
        raise ValueError("paired sequences must have equal length")
    b = sum(1 for x, y in zip(first, second) if x and not y)
    c = sum(1 for x, y in zip(first, second) if y and not x)
    n = b + c
    if n == 0:
        return Paired(b, c, 0.0, 1.0)
    z = (b - c) / math.sqrt(n)
    p = min(1.0, 2 * sum(math.comb(n, i) for i in range(min(b, c) + 1)) / 2**n)
    return Paired(b, c, z, p)


def percentile(values: Sequence[float], p: float) -> float | None:
    """Nearest-rank percentile; None when values is empty."""
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(math.ceil(p / 100 * len(ordered)), 1) - 1]
