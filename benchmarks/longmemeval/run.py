"""Command-line entry point of the LongMemEval benchmark: corpus | ingest | retrieve | qa | report.

This file is wiring only; the logic lives in the other modules of this directory.
Expected failures end as one ``error: ...`` line on stderr: exit code 1 for a
failed gate or run, 2 for a usage problem.
"""

import argparse
import dataclasses
import datetime
import json
import pathlib
import shlex
import subprocess
import sys
from collections.abc import Callable, Sequence

import arms
import dataset
import db
import hivemem
import ingest
import jsonl
import llm
import qa
import report

HERE = pathlib.Path(__file__).resolve().parent
SELFCHECK_THRESHOLD = 0.9
# Sidecar /info keys that do not change a vector: excluded from the run fingerprint.
_NOT_FINGERPRINTED = ("intra_op_threads",)


class UsageError(Exception):
    """A usage problem (exit code 2) detected after parsing."""


class RunError(RuntimeError):
    """A run-level check failed (exit code 1)."""


_RUN_ERRORS = (RunError, ingest.IngestError, arms.ArmError, qa.QaError, llm.LlmError,
               db.DbError, hivemem.HiveMemError, jsonl.JsonlError)


def build_parser() -> argparse.ArgumentParser:
    """The argument parser with one sub-command per stage."""
    parser = argparse.ArgumentParser(description="LongMemEval retrieval benchmark for HiveMem.")
    commands = parser.add_subparsers(dest="command", required=True)

    def command(name, help_text, *, data=False, stack=False, run_id=None):
        sub = commands.add_parser(name, help=help_text, description=help_text)
        sub.add_argument("--work-dir", type=pathlib.Path, default=HERE / ".work",
                         help="directory for downloaded data, state and runs (default: .work)")
        if data:
            sub.add_argument("--dataset", type=pathlib.Path, default=None,
                             help="dataset file (default: <work-dir>/data/longmemeval_s_cleaned.json)")
            sub.add_argument("--limit-questions", type=int, default=None, metavar="N",
                             help="stratified subset of N questions (default: all)")
        if stack:
            sub.add_argument("--hivemem-url", default="http://127.0.0.1:18421")
            sub.add_argument("--embedding-url", default="http://127.0.0.1:18080")
            sub.add_argument("--token-file", type=pathlib.Path, default=None,
                             help="file with the bearer token (default: <work-dir>/token)")
            sub.add_argument("--psql", default=None, metavar="COMMAND",
                             help="command that runs psql (default: the bench stack's db service)")
        if run_id is not None:
            sub.add_argument("--run-id", default=None, required=run_id,
                             help="run directory name under <work-dir>/runs")
        return sub

    command("corpus", "Print the corpus counts of the dataset.", data=True)
    ingest_cmd = command("ingest", "Write the corpus into the benchmark HiveMem.",
                         data=True, stack=True)
    ingest_cmd.add_argument("--settle-timeout", type=float, default=3600.0, metavar="SECONDS",
                            help="how long to wait for embeddings to settle (default: 3600)")
    retrieve = command("retrieve", "Rank every question with the retrieval arms.",
                       data=True, stack=True, run_id=False)
    retrieve.add_argument("--arms", default=",".join(arms.ARMS),
                          help="comma-separated arms (default: all)")
    qa_cmd = command("qa", "Answer and judge the questions of a run.", data=True, run_id=False)
    qa_cmd.add_argument("--arms", default=",".join(qa.QA_ARMS),
                        help="comma-separated arms (default: %(default)s)")
    qa_cmd.add_argument("--reader-model", default="claude-haiku-5-5")
    qa_cmd.add_argument("--judge-model", default="claude-sonnet-5-5")
    qa_cmd.add_argument("--workers", type=int, default=4)
    qa_cmd.add_argument("--no-cache", action="store_true", help="ignore cached answers and judgments")
    qa_cmd.add_argument("--selfcheck", action="store_true",
                        help="check the judge on gold answers (full dataset, needs no run)")
    qa_cmd.add_argument("--selfcheck-questions", type=int, default=40, metavar="N")
    command("report", "Render report.md of a run.", run_id=True)
    return parser


def read_token(path: pathlib.Path) -> str:
    """The stripped token in ``path``; exits with code 2 when missing or empty."""
    try:
        token = pathlib.Path(path).read_text(encoding="utf-8").strip()
    except OSError as error:
        raise SystemExit(_usage(f"cannot read token file {path}: {error}"))
    if not token:
        raise SystemExit(_usage(f"token file {path} is empty"))
    return token


def _usage(message: str) -> int:
    print(f"error: {message}", file=sys.stderr)
    return 2


def git_state(runner: Callable = subprocess.run) -> dict:
    """The commit and dirty flag of the checkout, ``None`` values when git is unavailable."""
    def git(*args: str) -> str:
        result = runner(["git", "-C", str(HERE), *args], capture_output=True, text=True)
        if result.returncode != 0:
            raise OSError(result.stderr)
        return result.stdout

    try:
        return {"commit": git("rev-parse", "HEAD").strip(),
                "dirty": bool(git("status", "--porcelain").strip())}
    except OSError:
        return {"commit": None, "dirty": None}


def new_run_id(now: datetime.datetime | None = None) -> str:
    """A UTC timestamp run id such as ``20261110-070809``."""
    return (now or datetime.datetime.now(datetime.timezone.utc)).strftime("%Y%m%d-%H%M%S")


def _now() -> str:
    # Microseconds: report.py orders qa_finished_at against retrieve_finished_at.
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def _read_json(path: pathlib.Path, what: str) -> dict:
    """The JSON object in ``path``; a missing, unreadable or non-object file is a usage error."""
    try:
        value = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise UsageError(f"cannot read {what} {path}: {error}") from error
    if not isinstance(value, dict):
        raise UsageError(f"{what} {path} is not a JSON object")
    return value


def fingerprint(corpus: dataset.Corpus, ingest_state: dict, sidecar_info: dict) -> dict:
    """The conditions a run's rankings were made under; a resumed run must match them."""
    return {"dataset_sha256": corpus.sha256, "limit_questions": corpus.limit_questions,
            "ingest_started_at": ingest_state.get("started_at"),
            "ingest_finished_at": ingest_state.get("finished_at"),
            "sidecar_info": {key: value for key, value in sidecar_info.items()
                             if key not in _NOT_FINGERPRINTED}}


def _invocations(meta: dict, command: str, git: dict) -> list[dict]:
    return list(meta.get("invocations", [])) + [
        {"command": command, "at": _now(), "commit": git["commit"], "dirty": git["dirty"]}]


def complete_arms(corpus: dataset.Corpus, rankings: dict) -> tuple[list[str], list[str]]:
    """Arms ranked for every non-abstention question, and arms only partly ranked."""
    present = {arm for _, arm in rankings}
    ordered = [arm for arm in arms.ARMS if arm in present] + sorted(present - set(arms.ARMS))
    scored = [q.id for q in corpus.questions if not q.is_abstention]
    complete = [arm for arm in ordered if all((qid, arm) in rankings for qid in scored)]
    return complete, [arm for arm in ordered if arm not in complete]


def update_meta(run_dir: pathlib.Path, **fields) -> dict:
    """Merge ``fields`` into ``meta.json`` of the run directory (created when missing)."""
    run_dir = pathlib.Path(run_dir)
    path = run_dir / "meta.json"
    meta = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    meta.update(fields)
    run_dir.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    return meta


def _load(args, limit: int | None):
    path = args.dataset or args.work_dir / "data" / "longmemeval_s_cleaned.json"
    try:
        return dataset.load(str(path), limit)
    except dataset.DatasetError as error:
        hint = "" if pathlib.Path(path).exists() else \
            " (see benchmarks/longmemeval/README.md for how to obtain the dataset)"
        raise UsageError(f"{error}{hint}") from error


def _csv(value: str) -> tuple[str, ...]:
    return tuple(part.strip() for part in value.split(",") if part.strip())


def _run_dir(args) -> pathlib.Path:
    directory = args.work_dir / "runs" / args.run_id
    if not directory.is_dir():
        raise UsageError(f"no run directory {directory}")
    return directory


def _cmd_corpus(args) -> int:
    corpus = _load(args, args.limit_questions)
    longest = max((len(doc.text) for doc in corpus.documents.values()), default=0)
    print(f"sha256={corpus.sha256} questions={len(corpus.questions)} "
          f"non_abstention={sum(1 for q in corpus.questions if not q.is_abstention)} "
          f"documents={len(corpus.documents)} longest_document_chars={longest}")
    if corpus.sha256 != dataset.EXPECTED_SHA256:
        print("warning: the dataset sha256 differs from the pinned dataset "
              f"({dataset.EXPECTED_SHA256})", file=sys.stderr)
    return 0


def _stack(args):
    token = read_token(args.token_file or args.work_dir / "token")
    mcp = hivemem.McpClient(args.hivemem_url, token)
    sidecar = hivemem.Sidecar(args.embedding_url)
    psql = db.Psql(shlex.split(args.psql) if args.psql else None)
    return token, mcp, sidecar, psql


def _cmd_ingest(args) -> int:
    corpus = _load(args, args.limit_questions)
    token, mcp, sidecar, psql = _stack(args)
    ingest.run_ingest(corpus, mcp, psql, sidecar, token, args.work_dir / "ingest.json",
                      settle_timeout=args.settle_timeout)
    return 0


def _cmd_retrieve(args) -> int:
    state_path = args.work_dir / "ingest.json"
    if not state_path.exists():
        raise UsageError(f"no ingest state at {state_path}: run ingest first")
    ingest_state = _read_json(state_path, "ingest state")
    if ingest_state.get("finished_at") is None:
        raise UsageError(f"ingest has not finished ({state_path} has no finished_at): "
                         "run ingest to completion first")
    corpus = _load(args, args.limit_questions)
    token, mcp, sidecar, psql = _stack(args)
    run_id = args.run_id or new_run_id()
    run_dir = args.work_dir / "runs" / run_id
    meta_path = run_dir / "meta.json"
    meta = _read_json(meta_path, "run metadata") if meta_path.exists() else {}
    current = fingerprint(corpus, ingest_state, sidecar.info())
    if meta or (run_dir / "rankings.jsonl").exists():
        recorded = meta.get("fingerprint")
        if recorded is None:
            raise RunError(f"run {run_id} has no recorded fingerprint; use a new --run-id")
        differing = sorted(key for key in set(recorded) | set(current)
                           if recorded.get(key) != current.get(key))
        if differing:
            raise RunError(f"run {run_id} was started under different conditions "
                           f"({', '.join(differing)}); use a new --run-id")
    git = git_state()
    update_meta(run_dir, dataset_sha256=corpus.sha256, questions=len(corpus.questions),
                documents=len(corpus.documents), limit_questions=corpus.limit_questions,
                ingest=ingest_state, fingerprint=current,
                started_at=meta.get("started_at") or _now(),
                commit=meta.get("commit", git["commit"]), dirty=meta.get("dirty", git["dirty"]),
                invocations=_invocations(meta, "retrieve", git))
    result = arms.run_retrieve(corpus, mcp, psql, sidecar, token, ingest_state, run_dir,
                               arms=_csv(args.arms))
    rankings = arms.load_rankings(run_dir / "rankings.jsonl")
    complete, partial = complete_arms(corpus, rankings)
    for arm in partial:
        print(f"warning: arm {arm} lacks rankings for some questions of the run; "
              "left out of retrieval.json", file=sys.stderr)
    try:
        summary = report.summarize_retrieval(
            corpus, {key: record for key, record in rankings.items() if key[1] in complete})
    except ValueError as error:
        raise RunError(f"cannot summarise the rankings of run {run_id}: {error}") from error
    (run_dir / "retrieval.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    fields = {"arms": {arm: result["arms"].get(arm, "not a harness arm") for arm in complete},
              "retrieve_sidecar_info": result["sidecar_info"], "retrieve_finished_at": _now()}
    if result["plans"]:  # a fully resumed run takes no plans; keep the recorded ones
        fields["plans"] = {**meta.get("plans", {}), **result["plans"]}
    update_meta(run_dir, **fields)
    path = report.write(run_dir)
    print(f"run id: {run_id}")
    print(f"report: {path}")
    return 0


def _cmd_selfcheck(args) -> int:
    corpus = _load(args, None)  # always the full dataset
    result = qa.run_selfcheck(corpus, args.work_dir / "cache", judge_model=args.judge_model,
                              count=args.selfcheck_questions, workers=args.workers,
                              use_cache=not args.no_cache, call=llm.call,
                              out=lambda message: None)  # the line below is the one printed
    print(f"judge self-check agreement: {result['agreement']:.3f} "
          f"({result['agreed']}/{result['checks']})")
    for item in result["disagreements"]:
        print(f"disagreement: {item['question_id']} {item['kind']} verdict={item['verdict_raw']!r}")
    return 0 if result["agreement"] >= SELFCHECK_THRESHOLD else 1


def _cmd_qa(args) -> int:
    if args.selfcheck:
        return _cmd_selfcheck(args)
    run_dir = _run_dir(args)
    meta_path = run_dir / "meta.json"
    if not meta_path.exists():
        raise UsageError(f"{run_dir} has no meta.json: run retrieve first")
    meta = _read_json(meta_path, "run metadata")
    if "dataset_sha256" not in meta or "limit_questions" not in meta:
        raise UsageError(f"{meta_path} records no dataset selection: run retrieve first")
    corpus = _load(args, meta["limit_questions"])  # the run's selection, whatever --limit-questions says
    if corpus.sha256 != meta["dataset_sha256"]:
        raise RunError(f"dataset sha256 {corpus.sha256} is not the dataset of run {args.run_id} "
                       f"({meta['dataset_sha256']})")
    if args.limit_questions is not None:
        if args.limit_questions < 1:
            raise UsageError(f"--limit-questions must be at least 1, got {args.limit_questions}")
        # A stratified subset of the run's questions; the documents stay the run's corpus.
        corpus = dataclasses.replace(
            corpus, questions=tuple(dataset.stratified(corpus.questions, args.limit_questions)))
    qa_arms = _csv(args.arms)
    claude_version = llm.cli_version()  # before any paid call
    update_meta(run_dir, invocations=_invocations(meta, "qa", git_state()))
    rankings = arms.load_rankings(run_dir / "rankings.jsonl")
    summary = qa.run_qa(corpus, rankings, run_dir, args.work_dir / "cache",
                        reader_model=args.reader_model, judge_model=args.judge_model,
                        arms=qa_arms, workers=args.workers,
                        use_cache=not args.no_cache, call=llm.call)
    update_meta(run_dir, claude_version=claude_version, reader=summary["reader"],
                judge=summary["judge"], qa_limit_questions=args.limit_questions,
                qa_arms=list(qa_arms), qa_finished_at=_now())
    print(f"report: {report.write(run_dir)}")
    return 0


def _cmd_report(args) -> int:
    run_dir = _run_dir(args)
    if not (run_dir / "retrieval.json").exists():
        raise UsageError(f"{run_dir} has no retrieval.json: run retrieve first")
    print(f"report: {report.write(run_dir)}")
    return 0


_COMMANDS = {"corpus": _cmd_corpus, "ingest": _cmd_ingest, "retrieve": _cmd_retrieve,
             "qa": _cmd_qa, "report": _cmd_report}


def main(argv: Sequence[str] | None = None) -> int:
    """Run one command; returns the process exit code."""
    # Progress must reach a log file or a background terminal as it happens.
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(line_buffering=True)
    args = build_parser().parse_args(argv)
    if args.command == "qa" and not args.selfcheck and not args.run_id:
        return _usage("qa needs --run-id (unless --selfcheck)")
    try:
        return _COMMANDS[args.command](args)
    except UsageError as error:
        return _usage(str(error))
    except _RUN_ERRORS as error:
        print(f"error: {' '.join(str(error).split())}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
