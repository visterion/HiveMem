"""Retrieval summary, the pre-registered headline rule and the ``report.md`` rendering.

``summarize_retrieval`` turns stored rankings into the ``retrieval.json`` content.
``render`` works from ``meta.json``, ``retrieval.json`` and (if present)
``qa.json`` alone; it never opens the dataset or ``rankings.jsonl``.
"""

import json
import pathlib
from collections.abc import Mapping, Sequence

import arms as arms_module
import dataset
import metrics

HEADLINE_METRIC = "recall_all@5"
GAIN = "HiveMem adds retrieval quality over its embedding model"
_ALPHA = 0.05
_TABLE_METRICS = ("recall_all@5", "ndcg_any@5", "recall_all@10", "ndcg_any@10",
                  "recall_any@5", "recall_all@50")
_META_FIELDS = (("dataset_sha256", "dataset sha256"), ("questions", "questions"),
                ("documents", "documents"), ("commit", "commit"), ("dirty", "dirty"))
_LENGTH_BUCKETS = ("<=8000", "8001-12000", "12001-16000", ">16000")
_SIZE_BUCKETS = ("1", "2", ">=3")


def _size_bucket(size: int) -> str:
    return str(size) if size < 3 else ">=3"


def _length_bucket(length: int) -> str:
    if length <= 8000:
        return "<=8000"
    if length <= 12000:
        return "8001-12000"
    if length <= 16000:
        return "12001-16000"
    return ">16000"


def _mean(values: Sequence[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def _group(flags: Mapping[str, list[float]]) -> dict:
    return {name: {"n": len(values), HEADLINE_METRIC: _mean(values) if values else None}
            for name, values in flags.items()}


def _diagnostics(questions, records, corpus, gold_of_selected) -> dict:
    outside, other_gold, late = [], [], []
    for question, record in zip(questions, records):
        top = record["ranking"][:5]
        outside.append(sum(1 for key in top if key not in question.haystack))
        other_gold.append(sum(1 for key in top
                              if key in gold_of_selected and key not in question.gold))
        late.append(sum(1 for key in top if key in corpus.documents and
                        dataset.date_for(question, corpus.documents[key]) > question.date))
    matches = [r["hits_with_match"] for r in records if r.get("hits_with_match") is not None]
    lengths = [len(r["ranking"]) for r in records if r.get("hits_with_match") is not None]
    recency_min = [r["recency_min"] for r in records if r.get("recency_min") is not None]
    recency_max = [r["recency_max"] for r in records if r.get("recency_max") is not None]
    return {"top5_outside_haystack": _mean(outside), "top5_other_gold": _mean(other_gold),
            "top5_after_question_date": _mean(late),
            "fewer_than_50": sum(1 for r in records if len(r["ranking"]) < 50),
            "match_share": sum(matches) / sum(lengths) if matches and sum(lengths) else None,
            "recency_min": min(recency_min) if recency_min else None,
            "recency_max": max(recency_max) if recency_max else None}


def summarize_retrieval(corpus: dataset.Corpus,
                        rankings: Mapping[tuple[str, str], Mapping]) -> dict:
    """The ``retrieval.json`` content; raises ``ValueError`` for an arm lacking a question."""
    questions = [q for q in corpus.questions if not q.is_abstention]
    present = {arm for _, arm in rankings}
    arm_names = [a for a in arms_module.ARMS if a in present] + sorted(present - set(arms_module.ARMS))
    for arm in arm_names:
        for question in questions:
            if (question.id, arm) not in rankings:
                raise ValueError(f"missing ranking for ({question.id}, {arm})")
    gold_of_selected = set().union(*(q.gold for q in corpus.questions)) if corpus.questions else set()
    summary_arms: dict[str, dict] = {}
    hits: dict[str, list[bool]] = {}
    for arm in arm_names:
        records = [rankings[(q.id, arm)] for q in questions]
        values = {f"{name}@{k}": [fn(r["ranking"], q.gold, k) for q, r in zip(questions, records)]
                  for k in metrics.KS
                  for name, fn in (("recall_any", metrics.recall_any),
                                   ("recall_all", metrics.recall_all),
                                   ("ndcg_any", metrics.ndcg_any))}
        hits[arm] = [v == 1.0 for v in values[HEADLINE_METRIC]]
        by_type: dict[str, list[float]] = {}
        by_size: dict[str, list[float]] = {name: [] for name in _SIZE_BUCKETS}
        by_length: dict[str, list[float]] = {name: [] for name in _LENGTH_BUCKETS}
        for i, question in enumerate(questions):
            score = values[HEADLINE_METRIC][i]
            by_type.setdefault(question.type, []).append(score)
            by_size[_size_bucket(len(question.gold))].append(score)
            longest = max(len(corpus.documents[key].text) for key in question.gold)
            by_length[_length_bucket(longest)].append(score)
        summary_arms[arm] = {
            "metrics": {name: _mean(vals) for name, vals in values.items()},
            "by_type": _group(dict(sorted(by_type.items()))),
            "by_gold_size": _group(by_size),
            "by_gold_length": _group(by_length),
            "diagnostics": _diagnostics(questions, records, corpus, gold_of_selected)}
    paired: dict[str, dict] = {}
    if "hivemem" in hits:
        for other in arm_names:
            if other != "hivemem":
                result = metrics.paired(hits["hivemem"], hits[other])
                paired[other] = {"b": result.b, "c": result.c, "z": result.z, "p": result.p}
    latencies = [r["latency_ms"] for (_, arm), r in rankings.items()
                 if arm == "hivemem" and r.get("latency_ms") is not None]
    summary = {"questions": len(questions),
               "unreachable_recall_all@5": sum(1 for q in questions if len(q.gold) > 5),
               "arms": summary_arms, "paired": paired,
               "latency_ms": {"p50": metrics.percentile(latencies, 50),
                              "p95": metrics.percentile(latencies, 95),
                              "max": max(latencies) if latencies else None}}
    summary["headline"] = headline(paired)
    return summary


def headline(paired: Mapping[str, Mapping]) -> str:
    """The fixed headline: a gain only when hivemem beats both controls with b > c, p < 0.05."""
    if "bm25" not in paired or "cosine" not in paired:
        return "Headline not available: the arms hivemem, bm25 and cosine are all required."
    not_beaten = [name for name in ("bm25", "cosine")
                  if not (paired[name]["b"] > paired[name]["c"] and paired[name]["p"] < _ALPHA)]
    if not not_beaten:
        return GAIN + "."
    return ("No retrieval gain shown: hivemem does not beat " + " and ".join(not_beaten)
            + " on recall_all@5.")


def _f(value) -> str:
    return "-" if value is None else f"{value:.3f}"


def _table(header: Sequence[str], rows: Sequence[Sequence[str]]) -> list[str]:
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join(" --- " for _ in header) + "|"]
    lines += ["| " + " | ".join(row) + " |" for row in rows]
    return lines + [""]


def _comparison_rows(paired: Mapping[str, Mapping]) -> list[list[str]]:
    return [[f"hivemem vs {name}", str(p["b"]), str(p["c"]), f"{p['z']:.3f}", f"{p['p']:.3f}"]
            for name, p in paired.items()]


def _breakdown(title: str, key: str, retrieval: Mapping) -> list[str]:
    arms = retrieval["arms"]
    names = list(dict.fromkeys(name for arm in arms.values() for name in arm[key]))
    rows = []
    for name in names:
        first = next(iter(arms.values()))[key].get(name, {})
        rows.append([name, str(first.get("n", 0))]
                    + [_f(arm[key].get(name, {}).get(HEADLINE_METRIC)) for arm in arms.values()])
    return [f"### {title}", ""] + _table(["group", "n"] + list(arms), rows)


def _qa_section(qa: Mapping) -> list[str]:
    lines = ["## Answer accuracy", "",
             f"Reader: requested {qa['reader']['requested']}, served "
             f"{', '.join(qa['reader']['served']) or '-'}.",
             f"Judge: requested {qa['judge']['requested']}, served "
             f"{', '.join(qa['judge']['served']) or '-'}.", ""]
    arms = qa["arms"]
    types = sorted({t for arm in arms.values() for t in arm["by_type"]})
    rows = [[name, str(arm["n"]), _f(arm["accuracy"])]
            + [_f(arm["by_type"].get(t, {}).get("accuracy")) for t in types]
            + [_f(arm["abstention"]["accuracy"])] for name, arm in arms.items()]
    lines += _table(["arm", "n", "accuracy"] + types + ["abstention"], rows)
    if qa.get("paired"):
        lines += ["Paired comparison of answer correctness:", ""]
        lines += _table(["comparison", "b", "c", "z", "p"], _comparison_rows(qa["paired"]))
    return lines


def render(run_dir: pathlib.Path) -> str:
    """Markdown report from ``meta.json``, ``retrieval.json`` and, if present, ``qa.json``."""
    run_dir = pathlib.Path(run_dir)
    load = lambda name: json.loads((run_dir / name).read_text(encoding="utf-8"))
    meta, retrieval = load("meta.json"), load("retrieval.json")
    qa = load("qa.json") if (run_dir / "qa.json").exists() else None
    lines = ["# LongMemEval retrieval report", "", "## Run facts", ""]
    facts = [(label, meta[key]) for key, label in _META_FIELDS if key in meta]
    model = ((meta.get("ingest") or {}).get("sidecar_info") or {}).get("model")
    if model is not None:
        facts.append(("embedding model", model))
    lines += [f"- {label}: {value}" for label, value in facts] or ["- (no metadata)"]
    lines += ["", "## Headline", "", f"**{retrieval['headline']}**", ""]
    if retrieval["paired"]:
        lines += _table(["comparison (recall_all@5)", "b", "c", "z", "p"],
                        _comparison_rows(retrieval["paired"]))
    arms = retrieval["arms"]
    lines += [f"## Retrieval ({retrieval['questions']} non-abstention questions)", ""]
    lines += _table(["arm"] + list(_TABLE_METRICS),
                    [[name] + [_f(arm["metrics"][m]) for m in _TABLE_METRICS]
                     for name, arm in arms.items()])
    lines += [f"recall_all@5 cannot reach 1 for {retrieval['unreachable_recall_all@5']} "
              "questions whose gold set has more than 5 documents.", "",
              "## Breakdowns (recall_all@5)", ""]
    lines += _breakdown("By question type", "by_type", retrieval)
    lines += _breakdown("By gold set size", "by_gold_size", retrieval)
    lines += _breakdown("By longest gold document (characters)", "by_gold_length", retrieval)
    lines += ["## Diagnostics", ""]
    keys = list(next(iter(arms.values()))["diagnostics"]) if arms else []
    lines += _table(["arm"] + keys,
                    [[name] + [_f(arm["diagnostics"][k]) if isinstance(arm["diagnostics"][k], float)
                               else ("-" if arm["diagnostics"][k] is None
                                     else str(arm["diagnostics"][k])) for k in keys]
                     for name, arm in arms.items()])
    latency = retrieval["latency_ms"]
    lines += ["## Latency (hivemem, ms)", "",
              f"p50 {_f(latency['p50'])}, p95 {_f(latency['p95'])}, max {_f(latency['max'])}", ""]
    if qa is not None:
        lines += _qa_section(qa)
    return "\n".join(lines).rstrip() + "\n"


def write(run_dir: pathlib.Path) -> pathlib.Path:
    """Write ``report.md`` into the run directory and return its path."""
    path = pathlib.Path(run_dir) / "report.md"
    path.write_text(render(run_dir), encoding="utf-8")
    return path
