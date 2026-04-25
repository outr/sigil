# Sigil MemBench Benchmark Results

**Date:** 2026-04-25 (initial run + post_processing retry)
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)
**Agent:** FirstAgent
**Retrieval:** vanilla cosine
**Score (7 runnable categories × 100 roles):** 656/700 (**93.7% R@5**)

## Per-category accuracy

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| aggregative | 100 | 100 | 100.0% |
| comparative | 100 | 100 | 100.0% |
| conditional | 97 | 100 | 97.0% |
| knowledge_update | 99 | 100 | 99.0% |
| noisy | 77 | 100 | 77.0% |
| post_processing | 84 | 100 | 84.0% |
| simple | 99 | 100 | 99.0% |

The first pass crashed on the very first `post_processing` role
(transient OpenAI 5xx in the embeddings endpoint); a standalone re-run
recovered the category cleanly. Detailed failures from that retry
live in [`membench-post_processing.md`](membench-post_processing.md).

The recommendation-category variants (`highlevel`, `highlevel_rec`,
`lowlevel_rec`) use a different schema (`{movie: [...]}` with
`mid`/`user` turn fields) and a different task (ranking) — they're
skipped, as in prior runs.

## Failure followup

Per-failure detail for the 16 `post_processing` misses is in
[`membench-post_processing.md`](membench-post_processing.md). For the
other 6 categories, the runner aborted before writing failures (28
total — derivable from per-category counts); a full re-run can recover
them if the analysis is needed.

## Infrastructure note

The crash root cause is `OpenAICompatibleEmbeddingProvider.embedBatch`
calling `response("data")` unconditionally. On a 5xx the response body
is `{"error": {...}}` with no `data` key, so fabric's `apply` throws.
Wrapping the embed call in retry-on-5xx (or returning a typed `Either`
instead of throwing on non-2xx bodies) would prevent a single OpenAI
hiccup from killing a 25-minute benchmark run.
