# Sigil Benchmark Scores

Measured scores across every benchmark sigil runs today. All share the same harness (`BenchmarkHarness` + `Retrieval` flags + `BenchmarkSigil`) and exercise `sigil.vector.HybridSearch` / `TemporalBoost` / `LLMReranker` as composable retrieval primitives. Headline numbers are **vanilla cosine** retrieval (no hybrid / temporal / rerank) — the most honest minimum-capability signal.

For the strategy around which benchmarks we run (and why), see [`../benchmarks.md`](../benchmarks.md).

---

## Memory retrieval

| Benchmark | Scope | Sigil score | Published reference | vs. target |
|---|---|---|---|---|
| **LongMemEval** | 500 questions, text-embedding-3-small | **99.4% R@5**, ~95.7% NDCG@5 | 96.6% raw / 98.4% hybrid / ≥99% +rerank | ✓ beats raw & hybrid targets |
| **LoCoMo** | 1,049 questions (basic_facts / temporal / abstention) | **91.3% R@10** | 60.3% session-only / 88.9% hybrid | ✓ beats hybrid target w/ cosine |
| **ConvoMem** | 250 questions (user_evidence sample) | 90.0% R@5 vanilla / 88.8% R@5 hybrid | 92.9% avg across 6 categories | close; full-category run pending |
| **MemBench** | 7 runnable categories × 100 roles (ACL 2025) | **93.6% R@5** | 80.3% overall | ✓ +13 pts over target |

**Per-category LongMemEval breakdown** (vanilla cosine, distinct-session scoring):

| Category | Accuracy |
|---|---|
| knowledge-update | 100.0% |
| single-session-user | 100.0% |
| single-session-assistant | 100.0% |
| single-session-preference | 100.0% |
| multi-session | 99.2% |
| temporal-reasoning | 98.5% |

**Per-category MemBench breakdown** (vanilla cosine):

| Category | Accuracy |
|---|---|
| aggregative | 100.0% |
| comparative | 100.0% |
| knowledge_update | 99.0% |
| conditional | 96.0% |
| simple | 53.0% |
| post_processing | (pending) |
| noisy | 77.0% |
| highlevel / highlevel_rec / lowlevel_rec | skipped — recommendation-category variant (different schema, out of scope for retrieval eval) |

## Tool use / function calling

| Benchmark | Model | Sigil score | Published reference |
|---|---|---|---|
| **BFCL v4 simple_python** | llamacpp/qwen3.5-9b-q4_k_m | **89.8%** | 80-87% for ~9B models |
| **BFCL v4 simple_python** | openai/gpt-5.4 | 84.8% | 88-93% top-tier models |
| **BFCL v4 simple_python** | openai/gpt-4o-mini | 84.3% | 80-87% small-class |

Scorer is a faithful port of BFCL's `ast_checker.py` (`standardize_string` normalization that strips `^ * . / - _ space` then lowercases, int↔float auto-coerce, dict/list/list-of-dict recursive matching, omission-via-`""`-sentinel rule). No system-prompt coaching beyond what BFCL's own OpenAI FC handler uses (which is nothing).

**Why qwen3.5-9b beats gpt-5.4 here:** gpt-5.4 is more helpful — it volunteers values for optional parameters, which BFCL penalizes because the allowed-values list for optional params is hand-curated and frequently rejects the model's reasonable guess. Qwen's conservative omission behavior happens to align with BFCL's scoring rubric. All three models hit an ~88-90% ceiling driven by dataset-convention mismatches (percentage-as-decimal, synonyms, plural/singular, date formats) that no model can infer from the schema. See `BFCLScorer.scala` for the exact normalization rules.

## Detailed reports

- [`longmemeval-vanilla.md`](longmemeval-vanilla.md) — full 500-question LongMemEval report with per-failure content detail.
