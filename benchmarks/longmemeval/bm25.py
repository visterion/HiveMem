"""Stdlib BM25 ranker over a keyed document collection."""

import math
import re
from collections import Counter
from collections.abc import Mapping


def tokenize(text: str) -> list[str]:
    """Lower-case word tokens."""
    return re.findall(r"\w+", text.lower())


class Bm25:
    """Inverted-index BM25; ties are broken by ascending document key."""

    def __init__(self, documents: Mapping[str, str], k1: float = 1.5, b: float = 0.75) -> None:
        self._k1 = k1
        self._b = b
        self._keys = sorted(documents)
        self._lengths: list[int] = []
        self._postings: dict[str, list[tuple[int, int]]] = {}
        for index, key in enumerate(self._keys):
            tokens = tokenize(documents[key])
            self._lengths.append(len(tokens))
            for term, tf in Counter(tokens).items():
                self._postings.setdefault(term, []).append((index, tf))
        self._average = sum(self._lengths) / len(self._lengths) if self._lengths else 0.0

    def rank(self, query: str, limit: int = 50) -> list[str]:
        """Keys of documents sharing a query term, best first, at most limit."""
        total = len(self._keys)
        scores: dict[int, float] = {}
        for term in set(tokenize(query)):
            postings = self._postings.get(term)
            if not postings:
                continue
            df = len(postings)
            idf = math.log(1 + (total - df + 0.5) / (df + 0.5))
            for index, tf in postings:
                norm = 1 - self._b + self._b * self._lengths[index] / self._average if self._average else 1.0
                scores[index] = scores.get(index, 0.0) + idf * tf * (self._k1 + 1) / (tf + self._k1 * norm)
        ordered = sorted(scores, key=lambda i: (-scores[i], self._keys[i]))
        return [self._keys[i] for i in ordered[:limit]]
