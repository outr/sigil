# Sigil Benchmarks

Benchmarking strategy, split into three tiers by how well each benchmark matches sigil's actual surface.

1. **Core fit** — tests primitives sigil actually ships (memory retrieval, tool-calling, multi-turn conversation). These are the ones we run.
2. **Requires app-level glue** — benchmarks that sigil *can* participate in but only once a consuming app supplies domain-specific tooling. Worth tracking for downstream consumers; not run from sigil's benchmark module.
3. **Out of scope** — benchmarks whose infrastructure belongs in an app, not a library (browser automation, OS control, container sandboxes). Listed here so the reasoning is documented, not to imply future work.

---

## Measured today

All benchmarks share the same harness (`BenchmarkHarness` + `Retrieval` flags + `BenchmarkSigil`) and exercise `sigil.vector.HybridSearch` / `TemporalBoost` / `LLMReranker` as composable retrieval primitives. Scores below are with vanilla cosine retrieval (no hybrid / temporal / rerank), which is the most honest minimum-capability signal.

### Memory retrieval

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

### Tool use / function calling

| Benchmark | Model | Sigil score | Published reference |
|---|---|---|---|
| **BFCL v4 simple_python** | llamacpp/qwen3.5-9b-q4_k_m | **89.8%** | 80-87% for ~9B models |
| **BFCL v4 simple_python** | openai/gpt-5.4 | 84.8% | 88-93% top-tier models |
| **BFCL v4 simple_python** | openai/gpt-4o-mini | 84.3% | 80-87% small-class |

Scorer is a faithful port of BFCL's `ast_checker.py` (`standardize_string` normalization that strips `^ * . / - _ space` then lowercases, int↔float auto-coerce, dict/list/list-of-dict recursive matching, omission-via-`""`-sentinel rule). No system-prompt coaching beyond what BFCL's own OpenAI FC handler uses (which is nothing).

**Why qwen3.5-9b beats gpt-5.4 here:** gpt-5.4 is more helpful — it volunteers values for optional parameters, which BFCL penalizes because the allowed-values list for optional params is hand-curated and frequently rejects the model's reasonable guess. Qwen's conservative omission behavior happens to align with BFCL's scoring rubric. All three models hit an ~88-90% ceiling driven by dataset-convention mismatches (percentage-as-decimal, synonyms, plural/singular, date formats) that no model can infer from the schema. See `benchmark/longmemeval-vanilla.md` for the detailed failure breakdown and `BFCLScorer.scala` for the exact normalization rules.

## Reports

- `benchmark/longmemeval-vanilla.md` — full 500-question LongMemEval report with per-failure content detail.

---

## Tier 1 — core fit (run from `benchmark/` subproject)

### Memory retrieval

Sigil *is* a memory library — every one of these tests something `sigil.vector` or `Sigil.searchMemories` exercises directly.

| Benchmark | Status | Notes |
|---|---|---|
| LongMemEval | implemented | HuggingFace `xiaowu0162/longmemeval-cleaned` |
| LoCoMo | implemented | via ConvoMem repo's `legacy_benchmarks/locomo` |
| ConvoMem | implemented | HuggingFace `Salesforce/ConvoMem` core_benchmark/pre_mixed_testcases |
| MemBench (ACL 2025, arXiv 2506.21605) | implemented | GitHub `import-myself/Membench` |
| **MemoryAgentBench** (ICLR 2026, [HUST-AI-HYZ/MemoryAgentBench](https://github.com/HUST-AI-HYZ/MemoryAgentBench)) | next | extends memory into an agent loop — requires orchestrator-driven rather than pure-retrieval harness |
| **REALTALK** | next | multi-turn dialogue memory; schema + dataset path to confirm |
| **MemoryBench** (arXiv 2510.17281 — continual learning for LLM systems) | next | adds a mutation/update dimension on top of retrieval |
| **MemoryArena** | deferred | need to confirm canonical source/schema |

### Tool use / function calling

Sigil's entire `Tool[Input]` + `ToolFinder` + `ConsultTool.invoke` machinery is exactly what these measure. Each requires porting the benchmark's tool catalog + eval script, but no new library concepts.

| Benchmark | Priority | Notes |
|---|---|---|
| **BFCL v4** (Berkeley Function Calling Leaderboard) | P0 | largest leaderboard; cleanest eval pipeline; best first port |
| **τ-bench** | P1 | interactive multi-turn tool use; strong agent-loop signal |
| **ToolSandbox** | P1 | sandboxed tool catalog; maps cleanly onto `InMemoryToolFinder` |
| **τ³-bench** | P2 | extends τ-bench |
| **WildToolBench** | P2 | larger / wilder tool catalog |

### Agent-loop safety

These test how an agent behaves under adversarial tool-use prompts — exactly what sigil's orchestrator + tool dispatch handles. AgentDojo specifically evaluates tool-injection defense. No new sigil primitives needed beyond what's already in place.

| Benchmark | Priority | Notes |
|---|---|---|
| **AgentDojo** | P0 of safety | attacks against tool-calling agents; fits the orchestrator path |
| **AgentHarm** | P1 | adversarial prompts over a tool-using agent |
| **Agent-SafetyBench** | P2 | ditto |
| **SafePro** | P2 | ditto |

---

## Tier 2 — requires app-level glue

Sigil can participate in these, but only once a consuming application supplies the domain-specific tools. Sigil could host an optional evaluation harness, but the tool catalogs and action spaces live above the library. Tracked here so downstream apps know what's available to target when their tool surface is in place.

| Benchmark | Category | What the app needs to supply |
|---|---|---|
| **GAIA** | general-agent | web + file-system tools; multi-modal capture |
| **AssistantBench** | general-agent | web-research tools |
| **TheAgentCompany** | general-agent | multi-step task tooling (email, calendar, etc.) |
| **Terminal-Bench** | coding | shell-sandbox tool family |
| **RepoQA** | coding | repo-scanning tools |
| **LiveCodeBench** | coding | code-execution sandbox |
| **BrowseComp** | general-agent | browser-automation tools |

Sigil's contribution when apps wire these up: the orchestrator, tool dispatch, provider abstraction, `ConsultTool.invoke`, and — when relevant — `MemoryExtractor` / `searchMemories` for memory-augmented variants.

---

## Tier 3 — out of scope for a memory/agent library

Infrastructure these require belongs in an application, not in sigil. Listed with rationale.

| Benchmark | Category | Why out of scope |
|---|---|---|
| **SWE-bench Verified / Live / Multilingual / Multi-SWE-bench** | coding | requires git + pytest + container sandboxes; coding-agent concerns are app-layer |
| **OpenHands Index** | coding | evaluates a specific agent framework, not a library |
| **WebArena / VisualWebArena** | browser | browser automation (Playwright) is app infrastructure |
| **OSWorld / AndroidWorld** | computer-use | OS-level control belongs in an app with UI bindings |
| **OS-Harm** | safety (computer-use) | depends on OSWorld-style infrastructure |
| **ClawsBench** | general-agent | unclear canonical source; deferred until verified |
| **Memora** | memory (tentative) | user flagged as tentative — no established benchmark page |

Apps that want any of these (e.g. a build that ships browser tools) can absolutely wire them against sigil's orchestrator. Sigil itself doesn't ship the infrastructure and doesn't run the benchmarks.

---

## Proposed phasing

**Phase 1 — finish the memory row.** Close out LongMemEval / ConvoMem / MemBench with full scores (vanilla + hybrid + rerank variants). Debug the ConvoMem runner. This ships the first honest "sigil parity numbers" table.

**Phase 2 — add the three next-up memory benchmarks.** MemoryAgentBench, REALTALK, MemoryBench. MemoryAgentBench specifically requires the harness to drive a real orchestrator loop, so this is also the moment `BenchmarkSigil` grows into a more capable `AgentBenchHarness` (multi-turn agents that can call tools and mutate memory across turns).

**Phase 3 — tool-use row.** BFCL v4 first. Define a `sigil.bench.tooluse` harness interface analogous to the memory harness. Port BFCL's catalog + scoring. Then τ-bench + ToolSandbox on the same harness.

**Phase 4 — safety row.** AgentDojo first (attacks the tool-call path). AgentHarm + peers follow the same harness pattern.

Tier 2 benchmarks (GAIA, AssistantBench, Terminal-Bench, etc.) land when a consumer supplies the tooling — not from sigil's own benchmark module.

---

## Open questions

1. **Results reporting** — three columns (vanilla / hybrid / rerank) or best-variant only in the headline table?
2. **Agent-loop harness shape** — extend `BenchmarkSigil` for MemoryAgentBench, or introduce `AgentBenchHarness` as a sibling?
3. **Dataset hosting** — add `SIGIL_BENCH_DATA` default that benchmark runners fall back to so CI / shared environments can point at one location?
4. **Score persistence** — commit results under `benchmarks/results/YYYY-MM-DD/` for longitudinal tracking, or leave them ephemeral?
