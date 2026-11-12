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
stage also needs the `claude` CLI, logged in. The harness is standard library only. The
dataset file is about 277 MB. The embedding service is limited to six cores and 14 GB of
memory by default (`cpus` and `mem_limit` in the compose file), sized for the large model;
adjust them to your host.

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

The embedding service and its image can be overridden through `LME_*` variables in `.env` (for
example `LME_EMBEDDINGS_IMAGE`, `LME_MODEL_REPO`, `LME_ONNX_FILE`, `LME_ORT_INTRA_OP_THREADS`);
see the compose file. The settings of the `hivemem` service (embedding timeout, chunk backoff,
sweep interval) are fixed in the compose file on purpose.

## Running

All commands run from the repository root. Outputs go to `benchmarks/longmemeval/.work/`.

```bash
# 1. Write the corpus into the bench HiveMem. About a day on CPU for the full set. An
#    interrupted ingest of the same selection is resumable: re-run the same command.
python3 benchmarks/longmemeval/run.py ingest

# 2. Rank every question with the five retrieval arms. Prints a run id and the report path.
#    An interrupted run is resumed with --run-id <run-id>.
python3 benchmarks/longmemeval/run.py retrieve

# 3. Check the judge on gold answers (needs no run). It must agree at 0.9 or better.
python3 benchmarks/longmemeval/run.py qa --selfcheck

# 4. Answer and judge the questions of a run with bm25, cosine, hivemem and oracle.
python3 benchmarks/longmemeval/run.py qa --run-id <run-id>

# 5. Re-render the report from the stored files (it never touches HiveMem).
python3 benchmarks/longmemeval/run.py report --run-id <run-id>
```

Use `--limit-questions N` on `ingest` and `retrieve` for a smoke run (for example
`ingest --limit-questions 5`), and `--arms a,b` to pick arms. The corpus of a limited run is
the union of the haystacks of the selected questions, so use the same `--limit-questions` on
both commands; `retrieve` checks the database against exactly that corpus. `qa` takes the
selection from the run's `meta.json` and refuses a dataset file whose sha256 differs from
the run's. On `qa`, `--limit-questions N` grades a stratified subset of N of the run's
questions, still answered from the run's rankings over the run's whole corpus; the report
says when the answer stage covers such a subset.

`retrieve --run-id <run-id>` resumes a run and skips the question and arm pairs already
ranked. It records the conditions of the run when the run starts (dataset hash, selection,
start and finish time of the ingest, and the embedding service's `/info` reply except its
thread count) and refuses to add to a run whose conditions differ; start a new run id then.
`retrieval.json` summarises every arm that has a ranking for every scored question of the
run, whichever `--arms` each invocation named; an arm that is only partly ranked is left out
and named on stderr.

**Never grow a smoke database into a measured run.** HiveMem scores recency from each cell's
`created_at`, so documents ingested earlier would be favoured and the default `hivemem` arm
would be measured on a skewed store. `ingest` therefore refuses to add to a database that
already holds documents unless `.work/ingest.json` shows they belong to the same dataset and
the same `--limit-questions`. After a smoke run, throw the stack away and start fresh before
the full `ingest`: run the `down -v` command below, then `up -d --build --wait` and the token
command of the Setup section again. The answer stage caches answers and judgments under
`.work/cache/`; `--no-cache` ignores the cache.

`ingest` writes its state file `.work/ingest.json` before the first write, with the dataset
hash, the selection, the embedding service's `/info` reply and an empty finish time, and
replaces it with the integrity figures and the finish time once the integrity gate has
passed. `retrieve` refuses to start while the finish time is empty.

A run lives in `.work/runs/<run-id>/`: `meta.json` (dataset hash, the run's recorded
conditions, the repository commit of the first invocation and one entry with commit and dirty
flag per `retrieve` or `qa` invocation, ingest integrity figures, the embedding service's
`/info` reply read by `retrieve`, SQL plans of the control arms with the query vector replaced
by `[<query vector>]`, model ids, timestamps), `rankings.jsonl`, `retrieval.json`, `qa.jsonl`,
`qa.json` and `report.md`. The report contains only public dataset ids and numbers.

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
- **Why `recall_all@5` cannot reach 1.** Three questions have six gold documents (after
  identical sessions are merged), which cannot fit into five slots. The report states the
  exact number of such questions.
- **Paired comparisons.** For `hivemem` against every other arm on `recall_all@5`: `b` is the
  number of questions only `hivemem` gets right, `c` the number only the other arm gets right,
  `z = (b - c) / sqrt(b + c)`, and `p` an exact two-sided binomial test on `b` and `c`.
- **Breakdowns of `recall_all@5`.** By question type, by number of gold sessions (1, 2, 3 or
  more), and by the length of the longest gold session.
- **Diagnostics.** Per arm: how many of the top five documents come from outside the
  question's own haystack; how many of the top five are a gold session of another selected
  question, wherever they lie (identical sessions are merged, so such a document can also be
  part of the question's own haystack); how many are dated after the question date; how many
  questions got fewer than 50 results; and how many got no result at all. For the `hivemem`
  arms it also shows the share of hits that carry a match snippet, the range of
  `score_recency` seen, and the latency percentiles.
- **The `oracle` arm** gives the reader the gold sessions themselves. Its accuracy is the
  ceiling set by reader and judge, independent of retrieval; compare the other arms against it.
- **Answer stage.** Accuracy per arm over the graded questions (all 500 unless `qa` was run
  with `--limit-questions`), per question type, and for the abstention questions separately,
  plus the paired comparison on correctness between `hivemem` and each of `bm25` and
  `cosine`. A group without questions shows `-`, not an accuracy. When the rankings changed
  after the answer stage ran, the report shows a stale notice instead of the answer tables.

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
  tokens per document. With the defaults of the GPU-oriented Ollama backend (8,000
  characters), most documents (63.5 %, 11,728 of the 18,464 merged documents, are longer
  than that) would get no cell vector at all, because the bench writes no summary. `ingest`
  refuses to start, and its integrity gate fails, when a gold document would have no cell
  vector or when more than 1 % of the documents would have none, which is what the
  8,000-character default would cause. The cells without a vector must also equal the
  documents longer than the embedding service's `max_chars`.

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
4. the embedding service reports its character limit, which is recorded; no gold document
   and at most 1 % of the documents are longer than it; and the embedding model HiveMem
   records for its stored vectors (the `embedding_model` row of its `identity` table) equals
   the model the embedding service reports;
5. when the realm already holds documents, the state file shows they belong to the same
   dataset and selection.

After the first successful write the psql channel must see that cell. When ingest is done, an
integrity gate must pass: exactly one cell per document, no cell without a vector except the
over-long documents, no chunk without a vector, and no cell still waiting for its chunks.

Before its first query, `retrieve` checks the token, the absence of cells outside the realm
(HiveMem's chunk candidate pool is global, so a foreign cell would change the `hivemem` arm),
the integrity gate, and that the embedding model equals both the one recorded by `ingest` and
the one recorded in HiveMem's `identity` table. It reads the embedding service's `/info` again
at the end; if the reply changed during the run, `rankings.jsonl` is moved to
`rankings.invalid.jsonl` and the command fails, so no later resume counts those rankings.

The harness reaches Postgres only through `docker compose exec -T db psql` of the bench
compose file; `--psql` overrides that command for experiments. The harness rejects a
`hivemem` reply in which no hit has a semantic score, which is the signature of HiveMem's
keyword-only fallback when the embedding service is unavailable, and stops instead of
recording it. The SQL plans of the control arms are stored in `meta.json`, and the harness
aborts if a control arm's plan contains an index or bitmap scan.

These guards were seen working on a live smoke run (5 questions, 238 documents). With the
embedding service stopped, HiveMem answered a `search` for an uncached query with HTTP 200
and 50 hits whose `score_semantic` was 0.0 throughout (keyword scores up to 0.99), and the
harness's reply validation rejected the reply; `retrieve` itself stopped at its gate because
the embedding service's `/info` was unreachable, and recorded nothing. The stored plans of
both control arms were sequential scans. The plan check runs again on every run, so the full
corpus is covered by the check itself, not by this observation.

## Tests

The unit tests use only the standard library, need no Docker, no network and no dataset:

```bash
python3 -m unittest discover -s benchmarks/longmemeval/tests
```

CI runs them on pushes to `main` and on pull requests into `main`. The benchmark itself
never runs in CI.

## Attribution

The dataset and the reader and judge prompt templates come from
[LongMemEval](https://github.com/xiaowu0162/LongMemEval) (MIT License, Copyright (c) 2024
Di Wu).

## Results

### Baseline 2026-11-12

This is the first full run (run id `baseline-2026-11-12`). Terms and columns are those
defined in "What this measures", "The arms" and [How to read the report](#how-to-read-the-report).

- Repository commit `5dcd996` (clean tree); dataset sha256 `d6f21ea9d60a0d56f34a05b609c79c88a451d2ae03597821ea3d5a9678c3a442`.
- 500 questions (470 scored for retrieval, 30 abstention questions), 18,464 documents.
- Embedding model: `snowflake-arctic-embed-l-v2.0/mrl0/t8192/c50000/cls/model_quantized-28ac86/noprefix/contentfirst` (int8-quantised ONNX model on CPU, 50,000-character embedding limit).
- Ingest: 2026-11-11T06:18Z to 2026-11-12T04:23Z (about 22 hours on a CPU-only development
  machine, 0.23 documents per second). The integrity gate passed with 18,464 cells, 77,287
  chunks, no chunk without a vector, and one document without a cell vector (it exceeds the
  embedding limit and is not a gold document).
- Retrieval: 2,500 question and arm pairs in 23 minutes.
- Answer stage: reader `claude-haiku-5-5`, judge `claude-sonnet-5-5`, both served as
  requested, through the `claude` CLI 2.1.322; the judge self-check, run separately before this run, agreed in 80
  of 80 cases (1.000).

#### Headline

**No retrieval gain shown: hivemem does not beat bm25 on recall_all@5.**

| comparison (recall_all@5) | b | c | z | p |
| --- | --- | --- | --- | --- |
| hivemem vs bm25 | 16 | 49 | -4.093 | 0.000 |
| hivemem vs cosine | 22 | 6 | 3.024 | 0.004 |
| hivemem vs chunkmax | 0 | 2 | -1.414 | 0.500 |
| hivemem vs hivemem-semantic | 0 | 2 | -1.414 | 0.500 |

The `p` column prints `0.000` for a value below 0.0005.

#### Retrieval

Recall and ranking quality on the 470 non-abstention questions (columns: [How to read the report](#how-to-read-the-report)).

| arm | recall_all@5 | ndcg_any@5 | recall_all@10 | ndcg_any@10 | recall_any@5 | recall_all@50 |
| --- | --- | --- | --- | --- | --- | --- |
| bm25 | 0.274 | 0.333 | 0.343 | 0.359 | 0.485 | 0.557 |
| cosine | 0.170 | 0.212 | 0.213 | 0.233 | 0.309 | 0.415 |
| chunkmax | 0.209 | 0.257 | 0.260 | 0.278 | 0.377 | 0.436 |
| hivemem | 0.204 | 0.255 | 0.257 | 0.278 | 0.372 | 0.438 |
| hivemem-semantic | 0.209 | 0.256 | 0.260 | 0.278 | 0.374 | 0.436 |

recall_all@5 cannot reach 1 for 3 questions whose gold set has more than 5 documents.

#### By question type

`recall_all@5` on the six LongMemEval question types.

| group | n | bm25 | cosine | chunkmax | hivemem | hivemem-semantic |
| --- | --- | --- | --- | --- | --- | --- |
| knowledge-update | 72 | 0.361 | 0.083 | 0.181 | 0.181 | 0.181 |
| multi-session | 121 | 0.091 | 0.033 | 0.066 | 0.066 | 0.066 |
| single-session-assistant | 56 | 0.929 | 1.000 | 0.982 | 0.982 | 0.982 |
| single-session-preference | 30 | 0.100 | 0.033 | 0.100 | 0.067 | 0.100 |
| single-session-user | 64 | 0.359 | 0.141 | 0.234 | 0.219 | 0.234 |
| temporal-reasoning | 127 | 0.110 | 0.031 | 0.031 | 0.031 | 0.031 |

#### By gold set size

`recall_all@5` by the number of gold documents of a question.

| group | n | bm25 | cosine | chunkmax | hivemem | hivemem-semantic |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 170 | 0.500 | 0.400 | 0.441 | 0.429 | 0.441 |
| 2 | 229 | 0.192 | 0.052 | 0.100 | 0.100 | 0.100 |
| >=3 | 71 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |

#### By longest gold document (characters)

`recall_all@5` by the length of the longest gold document of a question.

| group | n | bm25 | cosine | chunkmax | hivemem | hivemem-semantic |
| --- | --- | --- | --- | --- | --- | --- |
| <=8000 | 50 | 0.880 | 0.920 | 0.900 | 0.900 | 0.900 |
| 8001-12000 | 41 | 0.512 | 0.439 | 0.415 | 0.415 | 0.415 |
| 12001-16000 | 186 | 0.188 | 0.054 | 0.102 | 0.102 | 0.102 |
| >16000 | 193 | 0.150 | 0.031 | 0.088 | 0.078 | 0.088 |

#### Diagnostics and latency

| arm | top5_outside_haystack | top5_other_gold | top5_after_question_date | fewer_than_50 | empty_rankings | match_share | recency_min | recency_max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| bm25 | 4.417 | 0.353 | 1.232 | 0 | 0 | - | - | - |
| cosine | 4.657 | 0.317 | 1.279 | 0 | 0 | - | - | - |
| chunkmax | 4.560 | 0.387 | 1.238 | 0 | 0 | - | - | - |
| hivemem | 4.564 | 0.385 | 1.236 | 0 | 0 | 0.862 | 0.993 | 1.000 |
| hivemem-semantic | 4.562 | 0.389 | 1.240 | 0 | 0 | 0.855 | 0.993 | 1.000 |

`hivemem` latency in ms: p50 824.731, p95 2932.995, max 8353.321

#### Answer stage

Questions graded: 500 of the run's 500.

| arm | n | accuracy | knowledge-update | multi-session | single-session-assistant | single-session-preference | single-session-user | temporal-reasoning | abstention |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| bm25 | 500 | 0.418 | 0.551 | 0.233 | 0.946 | 0.267 | 0.471 | 0.308 | 0.733 |
| cosine | 500 | 0.314 | 0.269 | 0.173 | 1.000 | 0.233 | 0.314 | 0.211 | 0.733 |
| hivemem | 500 | 0.318 | 0.346 | 0.195 | 0.982 | 0.167 | 0.343 | 0.165 | 0.733 |
| oracle | 500 | 0.936 | 0.897 | 0.850 | 1.000 | 1.000 | 0.986 | 0.977 | 0.800 |

Paired comparison of answer correctness:

| comparison | b | c | z | p |
| --- | --- | --- | --- | --- |
| hivemem vs bm25 | 32 | 82 | -4.683 | 0.000 |
| hivemem vs cosine | 34 | 32 | 0.246 | 0.902 |

#### What the run shows

1. `hivemem` is below `bm25`, in retrieval (`recall_all@5` 0.204 against 0.274; 16 questions
   won, 49 lost) and in answer accuracy (0.318 against 0.418; 32 won, 82 lost). Both paired
   tests are significant (p below 0.001).
2. `hivemem` is above `cosine` in retrieval (0.204 against 0.170; 22 won, 6 lost, p = 0.004).
   The gain is not visible in the answers: accuracy 0.318 against 0.314 (34 won, 32 lost,
   p = 0.902).
3. On `recall_all@5`, `hivemem`, `hivemem-semantic` and `chunkmax` are within two questions
   of each other (0.204, 0.209, 0.209; `hivemem` won 0 and lost 2 against each of the other
   two). The six headline metrics of the three arms differ by at most 0.005, and no metric at
   depth five shows a gain of `hivemem` or `hivemem-semantic` over `chunkmax`. Deeper in the
   ranking the default weights are slightly ahead (`recall_all@30` 0.377 against 0.364 for
   both other arms, six questions). The step from `cosine` to `chunkmax` (chunking) is where
   the gain over the plain cell vector comes from.
4. The `oracle` arm reaches 0.936. Reader and judge are therefore not the main limit; the distance
   between 0.936 and the other arms is retrieval.
5. No arm gets all gold documents into the top five for any of the 71 questions with three or
   more gold documents.
6. The `hivemem` latency (p50 825 ms, p95 2,933 ms, max 8,353 ms) includes embedding the query
   on the CPU and was measured on a development machine. Where the time goes was not investigated.

#### Limits of this run

- One embedding model, int8-quantised, on CPU. A stronger model or finer chunking was not
  measured.
- One run. The paired tests cover question sampling, not run-to-run variation of the reader
  and judge.
- The length breakdown is confounded: gold document length, question type and gold set size
  are correlated, so it does not isolate an effect of length.
- The setting is one shared corpus (see
  [Why the numbers are not comparable with vendor headlines](#why-the-numbers-are-not-comparable-with-vendor-headlines));
  the numbers cannot be compared with per-question LongMemEval results.
