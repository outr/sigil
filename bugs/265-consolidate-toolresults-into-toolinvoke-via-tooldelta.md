# 💡 #265 — Consolidate `ToolResults` into `ToolInvoke` via `ToolDelta` (eliminate the paired-event pattern)

**Where:**

- `core/src/main/scala/sigil/event/ToolInvoke.scala` — gains an
  `output: Option[fabric.Json]` field (and any companion fields, e.g.
  `outcome`, that currently live only on `ToolResults`).
- `core/src/main/scala/sigil/signal/ToolDelta.scala` — gains an
  `output: Option[fabric.Json]` (plus `outcome`) and folds them into
  the target invoke via the existing `apply(target)` path.
- `core/src/main/scala/sigil/event/ToolResults.scala` — **removed** (or
  thin-deprecated shim) once callers migrate.
- `core/src/main/scala/sigil/tool/Tool.scala` — `buildResultEvent`
  emits a `ToolDelta(target = invoke.id, output = …, outcome = …,
  state = Complete)` instead of constructing a new `ToolResults` event.
- Every provider `*Provider.scala` — the events → wire converter folds
  the invoke's `output` into the matching `tool_result` block,
  rather than walking a separate `ToolResults` stream.
- `tome/lib/chat/sigil_chat_controller.dart` — `_upsertToolResults`
  and its origin-matching code go away; the chip simply rebuilds when
  the invoke's `output` lands via the existing `ToolDelta` handler.

**What's wrong with the current split.**

`ToolDelta` already folds every other lifecycle field of `ToolInvoke`
in place — `input`, `state`, `summary`, `usage`, `error`. The output
payload is the **only** field that lives on a separate event class
(`ToolResults`), linked back to its `ToolInvoke` via `origin`. That
inconsistency is the source of a recurring family of bugs:

- #259 — `AnthropicProvider` rendered the synthetic empty-output
  pairing for atomic-content tools (`respond`) twice → duplicate
  `tool_result` blocks → 400.
- #260 — empty / `null` `tool_use.input` decoded to `null` and the
  pair never got a result event.
- #261 — interleaved user-text between an assistant `tool_use` and
  its `tool_result` event, breaking Anthropic's adjacency requirement.
- #263 — tool-input parse failure left the `ToolInvoke` `Active` and
  emitted a `_provider_error` invoke also without a paired result;
  `renderFrames` correctly detected dangling tool calls and 400'd.

All four root-cause in "two events must be perfectly paired by
construction at every step — emission, persistence, wire-rendering,
chat-display." Every time the framework or a wire-converter touches
two events, there's an opportunity for them to drift.

If output were on `ToolInvoke` and folded via `ToolDelta`, **the pair
literally cannot drift** — there's one event, one identity, one chip.
The whole class of "dangling tool_call" / "duplicate tool_result" /
"orphaned synthetic pair" bugs becomes structurally impossible.

**Counter-arguments and their rebuttals.**

- *"Output size — `ToolInvoke` would bloat."* Pagination already
  exists. Only the first page lands in `output`; subsequent pages are
  retrieved via a fresh `next_page` tool call (a separate
  `ToolInvoke` with its own first page). Large file content is
  handled by `PaginatedTool`. There's no scenario where one invoke
  needs to carry an unbounded output blob; bound is bounded by
  `inlineContentThreshold`.

- *"Wire shape parity — providers split tool_use and tool_result."*
  This actually flips the argument. Anthropic specifically requires
  `tool_use` and `tool_result` to be **immediately adjacent in the
  message stream** (#261 was exactly this). Modelling them as one
  stateful event makes adjacency a structural guarantee — there's
  no way for an interleaved user-text event to break the pair,
  because there is no pair to break.

- *"Schema parameterisation — `ToolInvoke` is `[Input]`, adding
  `Output` complicates the case class."* `Tool` already handles
  both `Input` and `Output` as type parameters. `ToolInvoke` already
  type-erases through `ToolInput` / `fabric.Json`, so `output:
  Option[Json]` carries the typed payload the same way `input:
  Option[ToolInput]` does today. No new bi-parametric class needed.

**Proposed shape.**

```scala
case class ToolInvoke(toolName: ToolName,
                      // …existing fields…
                      input: Option[ToolInput] = None,
                      output: Option[ToolOutput] = None,     // NEW — typed, not Json
                      outcome: ToolOutcome = ToolOutcome.Success,  // NEW (moved from ToolResults)
                      summary: Option[String] = None,        // NEW (moved from ToolResults)
                      state: EventState = EventState.Active,
                      // …)

case class ToolDelta(target: Id[Event],
                     // …existing fields…
                     output: Option[ToolOutput] = None,     // NEW — typed, not Json
                     outcome: Option[ToolOutcome] = None)   // NEW
```

Note `output` is `Option[ToolOutput]` (the abstract parent of
`TextToolOutput`, `FindCapabilityOutput`, host-defined output types,
…) — **not** `Option[fabric.Json]`. This mirrors how
`input: Option[ToolInput]` already works: the event carries the typed
value, hosts register the concrete output RWs (a
`toolOutputRegistrations` companion to `toolInputRegistrations`), and
UI consumers can pattern-match on the concrete subtype rather than
parsing JSON. That's the whole point of the typed-tool design — losing
the type at the event boundary defeats the contract Tome's chip
relies on for type-aware rendering (markdown for `TextToolOutput`,
structured rendering for custom output types).

`Tool.buildResultEvent` becomes a `ToolDelta` emission rather than a
fresh event. `Provider.renderFrames` no longer needs to detect
"dangling tool_call" — there's no pair to dangle. The chat layer
simply re-renders the invoke when its `output` lands.

**Migration.**

- Step 1: add `output` / `outcome` to `ToolInvoke` and `ToolDelta`
  alongside the existing `ToolResults` path. Tools and provider
  adapters can emit either.
- Step 2: migrate emit sites tool-by-tool / provider-by-provider to
  the delta path.
- Step 3: once nothing emits or consumes `ToolResults`, remove the
  class. The chip merger goes with it.

`ToolResults` can ship a `@Deprecated` shim for one release so any
host listening for it gets a warning before removal.

**Severity.** Medium — meaningful refactor, but rooted in addressing a
recurring class of structural bugs (#259, #260, #261, #263). The
simplification ripples out: no `renderFrames` pair-tracking, no
`_upsertToolResults` origin-lookup in chat, no synthetic empty-output
pairing for atomic-content tools.
