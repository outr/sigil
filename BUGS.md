# Bugs

Tracking bugs found in Sigil, Spice, Fabric, and related libraries while building a downstream consumer on top of Sigil. Each entry: where it lives, what's wrong, what we observed, suggested fix.

**Status legend:** ⚠️ workaround in place · ❌ open · ✅ fixed.

Numbering preserves history — gaps reflect entries that were filed, fixed, and pruned.

## Sigil

### 6. ❌ Tool descriptions inline ALL `schema.examples` unconditionally — `respond` alone is ~1300 tokens of examples

**Where:** `~/projects/open/sigil/core/src/main/scala/sigil/provider/Provider.scala` — `renderDescription`. Affects every provider that renders tool descriptions for the LLM (LlamaCpp, OpenAI, Anthropic, Google, DeepSeek).

**Symptom:** Sage's greeting prompt for a fresh conversation with one `DefaultAgentParticipant` and `toolNames = CoreTools.coreToolNames` (the 5 essentials) lands at **5203 tokens**. Decomposed from the wire log:

| Section | chars | ~tokens |
|---|---|---|
| `respond` tool description | 5350 | ~1500 |
| `find_capability` tool description | 1954 | ~490 |
| `stop` tool description | 1053 | ~260 |
| `change_mode` tool description | 723 | ~180 |
| `no_response` tool description | 341 | ~85 |
| Tool parameter schemas (5 combined) | 1390 | ~350 |
| System prompt body (mode + topic + roles + boilerplate) | 1036 | ~250 |
| User/assistant messages | ~200 | ~50 |
| Qwen chat template framing (header + IMPORTANT block + im_start/end) | — | ~400-800 |

`respond` alone is ~30% of the total prompt. Breaking it down further: **658 chars of instructions + 4692 chars of 10 embedded examples**. Examples are 7× the actual description.

The current rendering (`Provider.renderDescription`) unconditionally inlines every `schema.examples` entry as compact JSON:

```scala
private def renderDescription[I <: ToolInput](schema: ToolSchema): String =
  if (schema.examples.isEmpty) schema.description
  else {
    val rendered = schema.examples.map { e =>
      val json = JsonFormatter.Compact(stripPolyDiscriminator(summon[RW[ToolInput]].read(e.input)))
      s"- ${e.description}: $json"
    }.mkString("\n")
    s"${schema.description}\n\nExamples:\n$rendered"
  }
```

`RespondTool` has 10 examples (topic-label-stable, label-refines, hard-switch, multi-content variants, etc.). For Qwen3.5 each example is a full `topicLabel`+`topicSummary`+JSON-encoded `content` — sizable. Smaller models benefit from many examples (better tool-call adherence), but charging the full set every turn is wasteful: every subsequent turn re-bills ~1300 tokens for the same examples the model already has demonstrated it can follow.

**Compounding factor for downstream consumers:** an agentic-coding tool like Sage that wants `find_capability`-discovered tools advertised at runtime will pay this on EVERY tool — every `find_capability` match adds another tool to the prompt with its own embedded examples, and prompt size scales linearly with discovered tool count. This is a built-in pressure against rich agent toolkits.

**Suggested fixes (any subset, ranked roughly by effort/impact):**

1. **Cap example count** in `renderDescription` — render at most N (default 3?) examples, in declaration order. Tool authors order most-instructive first. Tunable via a knob on `Sigil` (e.g. `def maxToolExamples: Int = 3`).
2. **Keep examples out of `description`; move them to a separate first-turn prompt section** that's included once when the model is shown a tool for the first time in a conversation, then dropped from subsequent turns. Significant complexity (per-conversation tracking of "which tools has the model seen") but big payoff for long conversations.
3. **Per-tool `examples` opt-in via a `ToolPolicy` knob** — apps that trust the model can disable examples globally; small-model setups keep them. Sage with Qwen3.5-9B would happily run with examples disabled because the model is decent at structured output without them.
4. **Compress the JSON in examples** — use multiline-pretty JSON only when verbose mode is on; default to compact, single-line, with field-name shortening. Marginal savings vs (1).

(1) is the smallest patch and the highest impact. The other 4 tools (no_response/change_mode/stop/find_capability) probably have ≤3 examples already; only `respond`'s 10-example set really benefits, and capping at 3 there would shave ~900-1000 tokens off every prompt.

**Sage-side state:** No workaround. With llama.cpp now configured `-c 131072` Sage has plenty of headroom, but every turn pays the ~1500-token `respond` description tax. Long conversations or large `find_capability` tool sets will compound the cost.
