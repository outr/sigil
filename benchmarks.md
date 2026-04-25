# Sigil Benchmarks

Benchmarking strategy, split into three tiers by how well each benchmark matches sigil's actual surface.

1. **Core fit** — tests primitives sigil actually ships (memory retrieval, tool-calling, multi-turn conversation). These are the ones we run.
2. **Requires app-level glue** — benchmarks that sigil *can* participate in but only once a consuming app supplies domain-specific tooling. Worth tracking for downstream consumers; not run from sigil's benchmark module.
3. **Out of scope** — benchmarks whose infrastructure belongs in an app, not a library (browser automation, OS control, container sandboxes). Listed here so the reasoning is documented, not to imply future work.

---

## Measured scores

See **[`benchmark/scores.md`](benchmark/scores.md)** for the current score table across every benchmark sigil runs, per-category breakdowns, and links to the detailed per-question reports.

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
| REALTALK (arXiv 2502.13270, [danny911kr/REALTALK](https://github.com/danny911kr/REALTALK)) | implemented | 10 real-world 21-day chats × 70-85 ground-truth questions; evidence is per-utterance `dia_id` refs (clean retrieval signal vs LLM-generated benchmarks) |
| **MemoryAgentBench** (ICLR 2026, [HUST-AI-HYZ/MemoryAgentBench](https://github.com/HUST-AI-HYZ/MemoryAgentBench)) | next | extends memory into an agent loop — requires orchestrator-driven rather than pure-retrieval harness |
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
