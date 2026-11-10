# LongMemEval benchmark

A stdlib-only harness that measures HiveMem's retrieval on
[LongMemEval](https://github.com/xiaowu0162/LongMemEval) (the `S` variant: 500 questions, each
with a haystack of roughly 48 chat sessions). Everything runs against a dedicated, disposable
HiveMem stack defined in this directory; it shares nothing with the main stack.

## What this measures

One question: **how much does HiveMem's retrieval add on top of the embedding model it runs
on?** A ranking, chunking or model change should be judged by a number instead of by feel.

The harness answers it in two stages on the same data:

1. **Retrieval stage, no LLM.** Every question is ranked against the sessions with several arms
   (see below) and scored with the upstream LongMemEval retrieval metrics. It is free and
   deterministic, and takes minutes once the corpus is ingested.
2. **Answer stage, LLM.** A fixed reader model answers each question from the top five sessions
   of an arm, and a fixed judge model grades the answer with the upstream judge prompts. Both
   are Claude models called through the local `claude` CLI, so no API key is involved.

The instrument is internal. It is not a leaderboard entry and not a marketing number.

## Why the numbers are not comparable with vendor headlines

Published LongMemEval numbers from memory-system vendors are end-to-end answer accuracy
with an LLM judge, but the answer model, the judge model, the judge prompt and the number of
retrieved items differ from vendor to vendor. One vendor moves from 84 % to 95 % by swapping
the reader model alone; another reports 93 % for itself while third parties measure 47 to
49 %. Retrieval-only headlines (for example 96.6 % `recall_any@5`) are computed with a fresh
vector index per question, no LLM and none of the product's own code. No vendor compares
itself with a plain vector store that uses the same embedding model. This README therefore
does not compare against any of them.

Two settings are worth keeping apart:

- **Upstream: one store per question.** Each question is asked against its own haystack of
  about 48 sessions. That setting is saturated: BM25 alone, with no model, reaches 0.968
  `recall_any@5`. There is little left to measure.
- **Here: one shared memory.** All 18,464 distinct sessions of the dataset live in one
  HiveMem, and all questions are asked against that one memory. That resembles real use (one
  large store) and is not saturated: BM25 reaches 0.274 `recall_all@5` and 0.485
  `recall_any@5`. The shared corpus pools the histories of many simulated users, so a session
  written for another question can be topically close to a question without being labelled as
  evidence. That lowers every arm's absolute numbers alike; the report counts how often it
  happens.

## The arms

Each arm returns at most 50 documents per question.

| Arm | What it is | What it isolates |
|---|---|---|
| `bm25` | BM25 inside the harness, no model | a lexical reference |
| `cosine` | exact cosine similarity on HiveMem's stored cell vectors, computed in SQL | the model alone: what a plain vector store returns |
| `chunkmax` | exact `max(cell, best chunk)` cosine, in SQL | the model plus HiveMem's chunking, without candidate pools or fusion |
| `hivemem-semantic` | HiveMem `search` with `weight_semantic=1` and all other weights 0 | what the approximate-nearest-neighbour pools and the relevance filter cost |
| `hivemem` | HiveMem `search` with its default weights | what an agent actually gets |

The chain `cosine`, `chunkmax`, `hivemem-semantic`, `hivemem` shows where a gain or a loss
comes from. The answer stage uses `bm25`, `cosine`, `hivemem` and `oracle` (the gold sessions
handed to the reader, an upper bound for the reader and judge).

The headline statement is fixed in advance. The report says "HiveMem adds retrieval quality
over its embedding model" only if `hivemem` beats **both** `bm25` and `cosine` on
`recall_all@5`, with more questions won than lost and an exact two-sided paired p-value below
0.05. Otherwise it names the control that was not beaten. Both comparisons are always printed
with their counts.

## Setup

You need Docker with Compose, Python 3.12 or newer, `curl`, and `openssl`. Running the answer
stage also needs the `claude` CLI, logged in. The stack uses about 14 GB of RAM for the
embedding service and a few GB of disk; the full ingest needs a CPU with at least six cores.

Start the stack from this directory. The database password is generated once and lives only
in a git-ignored `.env` file next to the compose file:

```bash
cd benchmarks/longmemeval
printf 'LME_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)" > .env
docker compose up -d --build --wait    # building the HiveMem image takes a while on a cold cache
curl -s http://127.0.0.1:18080/info    # the embedding model; "max_chars" must be 50000
```

The first start downloads the embedding model into a volume. When it finishes, HiveMem
listens on `127.0.0.1:18421` and the embedding service on `127.0.0.1:18080`.

Download the dataset and create a writer token for the harness. Both stay in `.work/`, which
is git-ignored:

```bash
mkdir -p .work/data
curl -L -o .work/data/longmemeval_s_cleaned.json \
  https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_s_cleaned.json
sha256sum .work/data/longmemeval_s_cleaned.json
docker compose exec -T hivemem hivemem-token create lme-bench --role writer | sed -n 's/^ *API token: //p' > .work/token
test "$(wc -c < .work/token)" -ge 40 && echo token-ok
cd ../..
```

The expected sha256 is `d6f21ea9d60a0d56f34a05b609c79c88a451d2ae03597821ea3d5a9678c3a442`.
Check that the harness reads the dataset (run from the repository root):

```bash
python3 benchmarks/longmemeval/run.py corpus
```

It prints `questions=500 non_abstention=470 documents=18464 …`.

All stack settings can be overridden through `LME_*` variables in `.env` (for example
`LME_EMBEDDINGS_IMAGE`, `LME_MODEL_REPO`, `LME_ONNX_FILE`, `LME_ORT_INTRA_OP_THREADS`); see the
compose file.

## Running

All commands run from the repository root. Outputs go to `benchmarks/longmemeval/.work/`.

```bash
# 1. Write the corpus into the bench HiveMem. About a day on CPU for the full set; it is
#    resumable (re-run the same command). Try it first with a stratified subset:
python3 benchmarks/longmemeval/run.py ingest --limit-questions 5
python3 benchmarks/longmemeval/run.py ingest

# 2. Rank every question with the five retrieval arms. Prints a run id and the report path.
python3 benchmarks/longmemeval/run.py retrieve

# 3. Check the judge on gold answers (needs no run). It must agree at 0.9 or better.
python3 benchmarks/longmemeval/run.py qa --selfcheck

# 4. Answer and judge the questions of a run with bm25, cosine, hivemem and oracle.
python3 benchmarks/longmemeval/run.py qa --run-id <run-id>

# 5. Re-render the report from the stored files (it never touches HiveMem).
python3 benchmarks/longmemeval/run.py report --run-id <run-id>
```

Use `--limit-questions N` on `retrieve` and `qa` as well for a smoke run, and `--arms a,b` to
pick arms. The corpus of a limited run is the union of the haystacks of the selected questions,
so ingest with the same `--limit-questions` first. The answer stage caches answers and
judgments under `.work/cache/`; `--no-cache` ignores the cache.

A run lives in `.work/runs/<run-id>/`: `meta.json` (dataset hash, repository commit, ingest
integrity figures, SQL plans of the control arms, model ids, timestamps), `rankings.jsonl`,
`retrieval.json`, `qa.jsonl`, `qa.json` and `report.md`. The report contains only public
dataset ids and numbers.

To throw everything away, including the database and the model cache:

```bash
docker compose -f benchmarks/longmemeval/docker-compose.yml down -v
```

## How to read the report

- **Metrics.** `recall_any@k` is the share of questions for which at least one gold session is
  in the top k; `recall_all@k` the share for which all of them are; `ndcg_any@k` is the
  rank-weighted gain, with the ideal ranking holding `min(k, number of gold sessions)`
  documents. They are computed on the 470 questions that have an answer in the haystack (the
  30 abstention questions have no gold session) for k in 1, 3, 5, 10, 30 and 50. The headline
  table shows `recall_all@5`, `ndcg_any@5`, `recall_all@10`, `ndcg_any@10`, `recall_any@5`
  and `recall_all@50`.
- **Why `recall_all@5` cannot reach 1.** Three questions have six gold sessions, which cannot
  fit into five slots. The report states the exact number of such questions.
- **Paired comparisons.** For `hivemem` against every other arm on `recall_all@5`: `b` is the
  number of questions only `hivemem` gets right, `c` the number only the other arm gets right,
  `z = (b - c) / sqrt(b + c)`, and `p` an exact two-sided binomial test on `b` and `c`.
- **Breakdowns of `recall_all@5`.** By question type, by number of gold sessions (1, 2, 3 or
  more), and by the length of the longest gold session.
- **Diagnostics.** Per arm: how many of the top five documents come from outside the
  question's own haystack, how many of those are another question's gold session, how many are
  dated after the question date, and how many questions got fewer than 50 results. For the
  `hivemem` arms it also shows the share of hits that carry a match snippet, the range of
  `score_recency` seen, and the latency percentiles.
- **The `oracle` arm** gives the reader the gold sessions themselves. Its accuracy is the
  ceiling set by reader and judge, independent of retrieval; compare the other arms against it.
- **Answer stage.** Accuracy per arm over all 500 questions, per question type, and for the
  abstention questions separately, plus the paired comparison on correctness between
  `hivemem` and each of `bm25` and `cosine`.

## Deliberate differences

From production HiveMem, on purpose:

- **No summarizer.** It needs an LLM. Cells are written with an empty summary, so the
  keyword vector lacks its highest-weighted part, and the single document longer than 50,000
  characters gets no cell vector (the embedding limit).
- **Embedding timeout 120 s** instead of the default 30 s. A single long document takes up to
  27 s without contention, and during ingest cell and chunk embedding compete for one sidecar.
- **Chunk backoff 1 minute and chunk sweep every 5 seconds**, so a throttled cell recovers
  while ingest waits for embeddings to settle. Neither value influences ranking.
- **Large embedding limits.** The embedding service takes up to 50,000 characters and 8,192
  tokens per document. The defaults of the GPU-oriented Ollama backend (8,000 characters)
  would silently embed most sessions only by their beginning.

From upstream LongMemEval:

- **Both roles are stored**, user and assistant turns, for every session.
- **Identical sessions are merged.** Hundreds of evidence sessions have a content twin under
  another id, so the corpus unit is the content, not the session id. A hit on a twin counts.
- **One shared corpus** instead of one store per question, and no official session ids reach
  HiveMem (they leak the label).
- **The judge verdict is parsed strictly.** The `claude` CLI offers no temperature or token
  cap, so a reply that does not start with a plain yes or no is not guessed at; unusable
  judgments stop the run instead of being counted.
- **Claude models through the `claude` CLI**, instead of the GPT models of the upstream
  scripts. The reader is `claude-haiku-5-5` and the judge `claude-sonnet-5-5`; absolute
  accuracies are therefore not comparable with upstream numbers.

## Safety guards

The harness writes 18,464 cells, so it refuses to write into anything but the bench stack.
Before the first write, `ingest` checks, and aborts if any check fails, that:

1. the SHA-256 of the token exists in the token table of the database reached through the
   psql channel, which ties the HiveMem endpoint being written to the database being guarded;
2. that database holds no cell outside the realm `longmemeval`;
3. every topic already in the realm belongs to the current selection, no topic occurs twice,
   and every cell of the realm is committed and current;
4. the embedding service reports its character limit, which is recorded.

After the first successful write the psql channel must see that cell. When ingest is done, an
integrity gate must pass: exactly one cell per document, no cell without a vector except the
over-long documents, no chunk without a vector, and no cell still waiting for its chunks.

The harness reaches Postgres only through `docker compose exec -T db psql` of the bench
compose file; `--psql` overrides that command for experiments. Retrieval stops with an error,
and appends no `hivemem` record, when the embedding service is unreachable, instead of
recording keyword-only results as if they were hybrid ones. The SQL plans of the control arms
are stored in `meta.json` and must show sequential scans.

## Tests

The unit tests use only the standard library, need no Docker, no network and no dataset:

```bash
python3 -m unittest discover -s benchmarks/longmemeval/tests
```

CI runs them on every push and pull request. The benchmark itself never runs in CI.

## Attribution

The dataset and the reader and judge prompt templates come from
[LongMemEval](https://github.com/xiaowu0162/LongMemEval) (MIT License, Copyright (c) 2024
Di Wu).

## Results

No baseline run recorded yet.
