# Sigil Benchmark Run — 2026-04-24/25 (overnight pipeline)

Vanilla cosine retrieval (no hybrid / temporal-boost / rerank) across the four
memory benchmarks; OpenAI `gpt-4o-mini` for BFCL. Per-benchmark detail and
failure listings live alongside this file.

Total wall time: 2h09m (LongMemEval 51m → LoCoMo 7m → ConvoMem 39m → MemBench
23m + 3m retry → BFCL 8m).

## Headline scores

| Benchmark | Score | Failures | Reference (scores.md prior to this run) | Detailed report |
|---|---|---|---|---|
| **LongMemEval** | 497/500 (**99.4% R@5**, 96.5% NDCG@5) | 3 | 99.4% (matches) | [longmemeval.md](longmemeval.md) |
| **LoCoMo** | 688/1049 (**65.6% R@10**) | 361 | 91.3% (large regression — see notes) | [locomo.md](locomo.md) |
| **ConvoMem** | 2000/2000 (**100.0% R@5**) | 0 | 90.0% (suspicious — see notes) | [convomem.md](convomem.md) |
| **MemBench** | 656/700 (**93.7% R@5**, 7 categories × 100 roles) | 44+ | 93.6% (matches) | [membench.md](membench.md) |
| **BFCL simple_python** (gpt-4o-mini) | 345/400 (**86.3%** accuracy, 0 no-tool-call) | 55 | 84.3% (slight improvement) | [bfcl-gpt4o-mini.md](bfcl-gpt4o-mini.md) |

## Per-category breakdowns

### LongMemEval (500 questions)

| Type | Correct | Total | Accuracy |
|---|---|---|---|
| knowledge-update | 78 | 78 | 100.0% |
| multi-session | 132 | 133 | 99.2% |
| single-session-assistant | 56 | 56 | 100.0% |
| single-session-preference | 30 | 30 | 100.0% |
| single-session-user | 70 | 70 | 100.0% |
| temporal-reasoning | 131 | 133 | 98.5% |

Three failures (Q72, Q303, Q304) — same as prior runs. Q72 (multi-session "led")
resolved by `--rerank --rerank-pool 50`; Q303/Q304 (temporal "N weeks/days ago")
need the `TemporalAnchor` primitive sketched in `design/benchmarking-wip.md`.

### LoCoMo (1049 questions, 30 dataset files × 3 categories)

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| basic_facts | 190 | 282 | 67.4% |
| temporal | 171 | 321 | 53.3% |
| abstention | 327 | 446 | 73.3% |

**Regression vs `scores.md` (91.3% claimed → 65.6% measured).** Per-file
breakdown shows real per-file variation, not gradual contamination — early
files (`dataset_0`, `dataset_1`) score 80-87%, later files drop to 50-65%.
The reset-collection logic appears correct; the discrepancy is either:
(a) the prior 91.3% was measured on a different file subset, or
(b) the dataset's later files are categorically harder than the early
ones and the prior run sampled only a portion.

Worth re-checking how that 91.3% was originally produced before
treating this as a real regression.

### ConvoMem (2000 questions sampled across batches)

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| abstention_evidence | 287 | 287 | 100.0% |
| assistant_facts_evidence | 541 | 541 | 100.0% |
| changing_evidence | 759 | 759 | 100.0% |
| user_evidence | 413 | 413 | 100.0% |

**100% / 0 failures looks too clean.** ConvoMem test cases have small
conversation counts and the runner takes top-k=5 conversations. If a test
has ≤5 conversations total, every conversation lands in top-K and "hit"
becomes trivially true. Worth instrumenting to log per-test conversation
counts, then either bumping k down (k=1?) or scoring at message-level
instead of conv-level. The prior 90.0% measurement on a 250-q sample
suggests there's a real signal we're masking.

`preference_evidence` and `implicit_connection_evidence` weren't reached
within the 2000-question cap.

### MemBench (700 roles — 7 runnable categories × 100 roles, FirstAgent)

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| aggregative | 100 | 100 | 100.0% |
| comparative | 100 | 100 | 100.0% |
| knowledge_update | 99 | 100 | 99.0% |
| simple | 99 | 100 | 99.0% |
| conditional | 97 | 100 | 97.0% |
| post_processing | 84 | 100 | 84.0% |
| noisy | 77 | 100 | 77.0% |

`noisy` is the consistent weak spot, followed by `post_processing`. The
recommendation variants (`highlevel`, `highlevel_rec`, `lowlevel_rec`) are
schema-incompatible with the retrieval-accuracy runner and remain skipped.

The initial pass aborted mid-`post_processing` on a transient OpenAI 5xx
in `embedBatch`. A standalone re-run recovered the category cleanly. The
embed-batch path doesn't retry on transient errors — wrapping it in
retry-on-5xx would prevent a single OpenAI hiccup from killing a 25-minute
benchmark run. Followup filed in [`membench.md`](membench.md).

### BFCL simple_python (400 cases, gpt-4o-mini)

345 correct / 0 no-tool-call / 55 wrong-args (86.3%).

**Slight improvement** vs `scores.md`'s 84.3% baseline (same harness, same
scorer, same model). The 55 failures sample from familiar dataset-convention
mismatches: percentage-as-decimal, plural/singular ("apple" vs "apples"),
synonyms ("buy" vs "purchase"), and BFCL's omission-via-empty-string sentinel
for optional params. See `bfcl-gpt4o-mini.md` for the per-case observed-vs-
expected list.

## Findings worth investigating

1. **LoCoMo regression** — score dropped 26 points from claimed 91.3% to measured
   65.6% on the full 1049-question set. Highest-priority puzzle.
2. **ConvoMem trivially 100%** — likely scoring at conversation-level with k=5
   on tests that have ≤5 conversations. Either drop k or score at message-level.
3. **`OpenAICompatibleEmbeddingProvider.embedBatch` resilience** — needs
   retry-on-5xx so transient OpenAI errors don't abort long-running runs.
   Concrete fix: wrap the post + parse in a retry of N attempts on
   5xx / on bodies missing `data`. Cost is minimal; benefit is
   benchmarks that complete instead of half-finishing.
4. **Per-failure detail missing for MemBench's first 6 categories** — the
   crash aborted before the report MD was written. A future re-run with
   embedBatch retry will produce the standard failure list.

## Run timeline

```
[21:04:43] LongMemEval start
[21:55:34] LongMemEval done (50m 51s) — 497/500 (99.4%)
[21:57:03] LoCoMo start
[22:04:06] LoCoMo done (7m 03s) — 688/1049 (65.6%)
[22:04:06] ConvoMem start
[22:43:15] ConvoMem done (39m 09s) — 2000/2000 (100.0%)
[22:43:15] MemBench start
[23:06:10] MemBench abort (22m 55s) — OpenAI embed 5xx
[23:06:10] BFCL start
[23:14:08] BFCL done (7m 58s) — 345/400 (86.3%)
[23:14:30] MemBench post_processing retry (3m 27s) — 84/100 (84.0%)
[23:18:00] aggregation complete
```
