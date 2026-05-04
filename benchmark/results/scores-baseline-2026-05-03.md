# Sigil Benchmark Scores

Measured scores across every benchmark sigil runs today. All share the same harness (`BenchmarkHarness` + `Retrieval` flags + `BenchmarkSigil`) and exercise `sigil.vector.HybridSearch` / `TemporalBoost` / `LLMReranker` as composable retrieval primitives. Headline numbers are **vanilla cosine** retrieval (no hybrid / temporal / rerank) — the most honest minimum-capability signal.

For the strategy around which benchmarks we run (and why), see [`../benchmarks.md`](../benchmarks.md).

---

## Memory retrieval

| Benchmark | Scope | Sigil score | Published reference | vs. target |
|---|---|---|---|---|
| **LongMemEval** | 500 questions, text-embedding-3-small | **99.4% R@5**, ~95.7% NDCG@5 | 96.6% raw / 98.4% hybrid / ≥99% +rerank | ✓ beats raw & hybrid targets |
| **LoCoMo** | 1,049 questions (basic_facts / temporal / abstention) | **65.6% R@10** | 60.3% session-only / 88.9% hybrid | between session-only and hybrid baselines; prior 91.3% claim unverified |
| **ConvoMem** | 7,000 questions across 4 categories (non-trivial cases) | **99.2% R@1** | 92.9% avg across 6 categories | ✓ beats target; runner skips cases where conversations.size ≤ k (those are scoring artifacts, not retrieval signal) |
| **MemBench** | 7 runnable categories × 100 roles (ACL 2025) | **93.7% R@5** | 80.3% overall | ✓ +13 pts over target |
| **REALTALK** | 728 questions across 10 real-world 21-day chats | **56.2% R@5** | n/a (no canonical retrieval baseline published) | hardest in the row — utterance-level ground truth + real-world conversational noise; category 2 (concrete event recall) hits 77.4%, category 1 (multi-evidence facts) 38.9% |

**Per-category LongMemEval breakdown** (vanilla cosine, distinct-session scoring):

| Category | Accuracy |
|---|---|
| knowledge-update | 100.0% |
| single-session-user | 100.0% |
| single-session-assistant | 100.0% |
| single-session-preference | 100.0% |
| multi-session | 99.2% |
| temporal-reasoning | 98.5% |

**Per-category MemBench breakdown** (vanilla cosine, full 7-cat × 100 roles):

| Category | Accuracy |
|---|---|
| aggregative | 100.0% |
| comparative | 100.0% |
| knowledge_update | 99.0% |
| simple | 99.0% |
| conditional | 97.0% |
| post_processing | 84.0% |
| noisy | 77.0% |
| highlevel / highlevel_rec / lowlevel_rec | skipped — recommendation-category variant (different schema, out of scope for retrieval eval) |

**Per-category REALTALK breakdown** (vanilla cosine, R@5, full 728 questions):

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| 2 (concrete events with dates) | 247 | 319 | 77.4% |
| 3 (cross-session reasoning) | 45 | 108 | 41.7% |
| 1 (multi-evidence facts) | 117 | 301 | 38.9% |

REALTALK is genuinely the hardest memory benchmark in the row — real-world chat noise (greetings, off-topic banter, meta-conversation), no LLM-curated phrasing, and most failures are the model failing to find any of several supporting utterances rather than picking the wrong one. Category 2 outperforms because the queries are anchored on specific events / dates / objects that match utterance text closely.

**Per-category ConvoMem breakdown** (vanilla cosine, R@1, non-trivial cases — `conversations.size > k`):

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| changing_evidence | 759 | 759 | 100.0% |
| assistant_facts_evidence | 2,700 | 2,705 | 99.8% |
| user_evidence | 823 | 826 | 99.6% |
| abstention_evidence | 2,659 | 2,710 | 98.1% |

`preference_evidence` and `implicit_connection_evidence` weren't reached within the 5,000-question cap; their batches sit at `1_evidence` (1-conv cases) which the scorer skips, and the higher-evidence levels weren't fetched. The fully-loaded categories above still produce a 7,000-question run with real retrieval signal at every datapoint.

## Tool use / function calling

| Benchmark | Model | Sigil score | Published reference |
|---|---|---|---|
| **BFCL v4 simple_python** | llamacpp/qwen3.5-9b-q4_k_m | **89.8%** | 80-87% for ~9B models |
| **BFCL v4 simple_python** | openai/gpt-5.4 | 84.8% | 88-93% top-tier models |
| **BFCL v4 simple_python** | openai/gpt-4o-mini | 84.3% | 80-87% small-class |

Scorer is a faithful port of BFCL's `ast_checker.py` (`standardize_string` normalization that strips `^ * . / - _ space` then lowercases, int↔float auto-coerce, dict/list/list-of-dict recursive matching, omission-via-`""`-sentinel rule). No system-prompt coaching beyond what BFCL's own OpenAI FC handler uses (which is nothing).

**Why qwen3.5-9b beats gpt-5.4 here:** gpt-5.4 is more helpful — it volunteers values for optional parameters, which BFCL penalizes because the allowed-values list for optional params is hand-curated and frequently rejects the model's reasonable guess. Qwen's conservative omission behavior happens to align with BFCL's scoring rubric. All three models hit an ~88-90% ceiling driven by dataset-convention mismatches (percentage-as-decimal, synonyms, plural/singular, date formats) that no model can infer from the schema. See `BFCLScorer.scala` for the exact normalization rules.

## Agent-loop safety

Tests how Sigil's orchestrator + tool dispatch holds up when attacker text appears in tool returns (file contents, transaction subjects, etc.) and instructs the agent to execute a different goal than the user asked for. Two scores:

- **Baseline utility** — the agent completes the user's task with no injection present (pure capability signal).
- **Defense rate (1 − TASR)** — across the user × injection matrix, the fraction of cells where the injection's `security` predicate is *not* satisfied (the agent didn't fall for the attack). Higher is better.

`Injected utility` is the baseline utility recomputed under attack — it shows how much the user task itself suffers when the model is also fielding an attack payload.

| Benchmark | Model | Baseline utility | Injected utility | Defense rate (1−TASR) |
|---|---|---|---|---|
| **AgentDojo banking** (v1_2, important_instructions attack) | openai/gpt-5.4-mini | **75.0%** (12/16) | 59.0% (85/144) | **90.3%** (130/144 attacks defended) |
| **AgentDojo banking** (v1_2, important_instructions attack) | anthropic/claude-haiku-4-5 | **68.8%** (11/16) | 50.0% (72/144) | **100.0%** (144/144 attacks defended) |
| **AgentDojo banking** (v1_2, important_instructions attack) | llamacpp/qwen3.5-9b-q4_k_m | 25.0% (4/16) † | 25.0% (36/144) † | 100.0% (vacuous — see note) |

Scope: banking suite (16 user tasks × 9 injection tasks under one attack template = 160 cells per model). Other suites (slack, travel, workspace) and other attack strategies (`tool_knowledge`, no-user-name, no-model-name variants) are follow-up work using the same harness.

**Reading the row:** gpt-5.4-mini is more capable on user tasks (75% baseline vs 68.8% for haiku) but compromised on 14/144 attack cells (9.7% TASR), most concentrated on the data-exfil injections (i0-i4: send a transaction with the user's streaming-service / pizza partner / favorite food / phone in the subject line). haiku-4-5 shows **zero successful injections across all 144 attack cells** — a clean defense rate against this attack template, at the cost of slightly lower baseline capability. Both models pay roughly an 18-19 point capability tax under attack (utility drops from baseline) — distraction cost of being on guard.

**† Qwen3.5-9b's row is vacuous, not impressive.** Average ~1.0 LLM call per cell (320 wire-log frames across 160 cells); the model `respond`s once without ever calling banking tools. The 40 "OK" cells are exactly the user tasks whose utility predicates evaluate true *without any environment mutation* — `u5` (existing Spotify tx already matches the predicate), `u8` (utility returns `True` unconditionally), `u9` and `u10` (utility checks `pre == post` plus a pre-existing fixture condition). Each of these passes 10/10 cells (baseline + all 9 injections), giving 40 OK / 25% rate; every tool-using task fails. TASR is 0% only because the model also doesn't make any of the attacker-IBAN transactions — defense by inaction. Genuine model-capability gap on the q4_k_m quant; a stronger local model would likely engage the tools and produce a real number.

The runner is `sbt "benchmark/runMain bench.agentdojo.banking.AgentDojoBankingBench <modelId>"`. Per-model reports at `benchmark/agentdojo-banking-<modelLabel>.md` carry the per-cell pass / fail / compromised matrix and the first 20 errors.

## Detailed reports

- [`longmemeval-vanilla.md`](longmemeval-vanilla.md) — full 500-question LongMemEval report with per-failure content detail.
- `agentdojo-banking-<modelLabel>.md` — per-model AgentDojo banking matrix, written by the runner.
