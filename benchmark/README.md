# `benchmark/` — sbt subproject

Runners for sigil's memory-retrieval benchmarks. Current measured scores: [`scores.md`](scores.md). Strategy around which benchmarks we run and why: top-level [`benchmarks.md`](../benchmarks.md).

## Prerequisites

- Qdrant reachable at `SIGIL_QDRANT_URL` (default `http://localhost:6333`)
- `OPENAI_API_KEY` for embeddings (and for `--rerank`)
- Dataset files downloaded yourself (paths below)

Optional:
- `SIGIL_EMBEDDING_MODEL` (default `text-embedding-3-small`)
- `SIGIL_EMBEDDING_DIMENSIONS` (default `1536`)
- `OPENAI_BASE_URL` (default `https://api.openai.com`) for OpenAI-compatible hosts

## Retrieval flags (shared across all runners)

| Flag | Effect |
|---|---|
| `--hybrid` | Enable semantic + keyword hybrid scoring. |
| `--hybrid-weight D` | Semantic weight (0.0 = pure keyword, 1.0 = pure semantic; default 0.7). |
| `--temporal-boost` | Rerank by stored-timestamp proximity to the query reference time. |
| `--temporal-halflife DAYS` | Half-life for temporal decay (default 7). |
| `--temporal-weight D` | Blend weight for the temporal boost (default 0.3). |
| `--rerank` | LLM rerank of top-N candidates (requires `--rerank-model`). |
| `--rerank-model MODEL` | Model id routed via sigil's provider (e.g. `openai/gpt-4o-mini`). |
| `--rerank-pool N` | Candidate pool before rerank cuts to `--k` (default 20). |

## MemoryArmsBench — arms-and-controls memory evaluation

Self-contained (no dataset download, no Qdrant): a persona corpus answered by a small
runtime model under five configurations, scored on the settled conversation trace.

```sh
sbt "benchmark/runMain bench.MemoryArmsBench [--limit N] [--arms baseline,passive,agentic,distilled,split,stuffed] [--report PATH] [--verbose]"
```

- **Corpus shape**: dense passages (three facts per paragraph — what document ingestion
  actually produces), one memory per passage in every memory arm except `split`.
- **Arms**: `baseline` (no memory — the floor), `passive` (StandardMemoryRetriever injection),
  `agentic` (`semantic_search` tool, no injection), `distilled` (passive over a
  `ConsultMemoryDistiller` 1:1-rewritten corpus), `split` (passive over an
  `ingestCorpusMemories` corpus — each passage split into atomic single-fact memories),
  `stuffed` (every passage jammed into the user message — the naive control the machinery
  must beat on tokens while matching on accuracy).
- **Scoring**: answerable questions score accuracy (any-of gold substrings); an adversarial
  tier of unanswerable questions scores hedging separately (heuristic marker match) — a
  confident answer to a question the corpus can't answer is confabulation, not helpfulness.
- **Isolation**: each arm seeds its corpus into its own `SpaceId` and only that space is
  accessible during the arm — no cross-arm recall (the contamination class that quietly
  flatters baselines).
- **Fusion sweep**: `--lexical-weights 0,1,2,4` re-runs the retriever arms (`passive`,
  `distilled`, `split`) once per lexical RRF weight, each in its own space; `--vector-weight`
  / `--keyword-weight` hold the other legs. The `recall@5` column is the retrieval-level
  score — did the gold fact reach the prompt — which the weights move directly; accuracy
  sits downstream of it and adds the runtime model's variance.
- **Runtime**: `SIGIL_LLAMACPP_HOST` (default the public endpoint) and
  `SIGIL_LLAMACPP_MODEL`. With `OPENAI_API_KEY` set, the vector leg runs via
  OpenAI-compatible embeddings + an in-memory index; without it, retrieval is lexical-only
  and the report says so. `ARMS_DEBUG=1` prints the retrieval question, per-stage leg sizes,
  and the curated injection per turn.

## Runners

```sh
# LongMemEval — huggingface xiaowu0162/longmemeval-cleaned
sbt "benchmark/runMain bench.LongMemEvalBench /path/to/longmemeval_s_cleaned.json [--limit N] [--k N] [--indices I,J,K] [--report PATH]"

# LoCoMo — from ConvoMem's legacy_benchmarks/locomo directory
sbt "benchmark/runMain bench.LoCoMoBench /path/to/locomo-dir [--limit N] [--k N] [--category N]"

# ConvoMem — huggingface Salesforce/ConvoMem, core_benchmark/pre_mixed_testcases
sbt "benchmark/runMain bench.ConvoMemBench /path/to/pre_mixed_testcases [--limit N] [--k N] [--category X] [--max-questions N] [--batch N]"

# MemBench — github.com/import-myself/Membench, MemData/
sbt "benchmark/runMain bench.MemBenchBench /path/to/MemData [--limit N] [--k N] [--category CAT] [--agent FirstAgent|ThirdAgent]"

# REALTALK — danny911kr/REALTALK, data/Chat_*.json (10 real-world 21-day chats)
sbt "benchmark/runMain bench.RealTalkBench /path/to/realtalk-data [--limit N] [--k N] [--max-questions N] [--report PATH]"
```

LongMemEval writes a markdown report to `--report PATH` (default `benchmark-results.md`); the others print to stdout only.
