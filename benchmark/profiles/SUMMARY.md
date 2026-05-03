# Phase 0 — Context Profile Summary

Cross-scenario analysis of token contribution by section. Drives the
shed-order decisions for the multi-stage curator and provider pre-flight
gate (Phase 1 of the context-limit-handling plan).

## Per-scenario top contributors

| Scenario | Top section | Share | 2nd | Share | Comment |
|---|---|---|---|---|---|
| Long conversation (50 turns, no tools) | Frames | 71% | ToolRoster | 14% | Frames grow 79 tok/turn linearly. |
| Tool heavy (25 tools + cycles) | Frames | 55% | ToolRoster | 30% | Tool descriptions are a real fixed cost. |
| Memory heavy (100 critical + 50 retrieved) | CriticalMemories | **59%** | Memories | 26% | Memories together = 85%. |
| Mode switching | ToolRoster | 41% | Instructions | 33% | Skills + roles surprisingly modest (~8%). |
| Compression trigger (40 turns × 1.5KB results) | Frames | **93%** | ToolRoster | 3% | Verbose ToolResults dominate. Each turn = 540 tok. |

## Compression effectiveness

`CompressionTriggerBench` shows the existing frame-compression saves ~7,500 tokens
at the trigger point (turn 26: 22,413 → 14,313 tokens). p95 drops 35%. The
summary itself is tiny (38 tokens) — net win is enormous.

## Cross-scenario constants

These overheads are stable per-turn regardless of conversation length:

| Section | Tokens (typical) | Notes |
|---|---|---|
| Instructions | ~370 | Independent of conversation; same across all scenarios. |
| ToolRoster (minimal: respond / find_capability / stop) | ~400 | Three core tools' descriptions. |
| ToolRoster (25 tools) | ~870 | +475 for 22 fake tools — about 21 tok/tool. |
| ModeBlock | ~30 | Current mode + topic header. |
| ToolFramingPrefix | ~22 | The "always pick a tool" preamble. |
| Suggested tools (when populated) | ~13 | 1-turn ephemeral; not a shed target. |

## Locked shed order (confirmed by data)

In order of "drop first":

1. **Memories (non-critical, retrieved this turn)** — large share when populated (26% of memory-heavy total). Recoverable via `recall_memory`. ✓ confirmed
2. **Information records not referenced in current frames** — same logic; recoverable via `lookup`. (Not exercised in benches; carrying through from plan.)
3. **Frame compression via `MemoryContextCompressor`** — 71-93% of long-conversation totals. The biggest single lever. Saves ~35% of p95 at the trigger point per measured data. ✓ confirmed
4. **Tool-roster trimming** (NEW from data — was not in initial plan):
   - 14-30% share constant across scenarios.
   - Strategies (low → high aggression): drop tools whose `descriptionFor` matches a static cap (e.g. take-first-sentence); drop entire tools not in `recentTools` or `suggestedTools` for this turn (baseline framework essentials always retained).
   - Worth implementing because the tool roster is the largest CONSTANT-cost section; cuts every turn's overhead, not just over-budget turns.

After all four stages: if request is still over budget, **`RequestOverBudgetException`**.

## Critical memories — never dropped

Critical memories (`MemorySource.Critical`) are inviolable. Apps pin them as
"must be in context every turn"; dropping silently would betray that contract
(safety directives, compliance rules, persona invariants).

The framework's responsibility to keep them small without dropping them:

1. **Render `summary || fact`** — when `summary` is non-empty, surface that in
   the `Critical directives` section instead of the full `fact`. Apps that
   want a tight directive surface set the summary; full text remains available
   via `lookup(capabilityType=Memory, name=key)`. Backward-compatible: empty
   summary = current behavior.
2. **Write-time warning** — `persistMemory` logs when a Critical memory's fact
   exceeds a configurable token threshold (default ~150) AND no summary is
   set, recommending the author trim or summarise.
3. **Documentation** — CLAUDE.md note: critical directives render every turn;
   keep them concise; full detail recoverable via `lookup`.

## Sections NEVER shed

Confirmed by data — small, load-bearing, agent's identity:

- ToolFramingPrefix (~22 tok)
- ModeBlock + Topic (~30 tok)
- Roles (~20 tok in scenarios that exercise them)
- ActiveSkills (~70-100 tok; load-bearing for current task)
- Instructions (~370 tok — already minimal; further trim risks model drift)
- Suggested tools (~13 tok and 1-turn ephemeral anyway)

## Implications for Phase 1

The plan's curator stages are sound; one addition:

- **Add "tool-roster trimming" between stage 3 (frame compression) and stage 4 (critical-memory drop).** Two implementations:
  - Cheap: cap each tool's description to N tokens (configurable; default ~30, the first sentence).
  - Aggressive: drop entire tools whose names aren't in the agent's `recentTools ∪ suggestedTools ∪ frameworkEssentials` set.

- **Tokenizer note**: heuristic vs jtokkit comparison wasn't run in this phase but the bench infrastructure supports it — switching `ProfilerHarness.tokenizer` to `HeuristicTokenizer` and re-running would surface the gap. For Phase 1 implementation, real per-provider tokenizers are still the right design — heuristic is good for offline analysis but production budget enforcement wants accuracy.
