# ❌ #191 — Add reactive `summary: Var[String]` to `TurnContext` so tools can drive chat-UI chip detail across their execution arc

**Where:**
- `core/src/main/scala/sigil/TurnContext.scala` — the per-invocation
  handle every tool's execution receives. Gains the new `summary`
  field.
- A new wire event (`SummaryDelta`, or an extension of `ToolDelta`
  carrying an `Option[String]` summary update) that targets a
  `ToolInvoke._id` and carries each `summary.set(...)` value.
- Wire-policy / framework consumers register a listener on the
  reactive var that emits a `SummaryDelta` on every set, plumbed
  through the existing Delta dispatch so chat UIs receive updates
  in real time.
- `core/src/main/scala/sigil/event/ToolResults.scala` — existing
  `summary: Option[String]` field becomes a derived projection
  ("the final value of `TurnContext.summary` at result-emit time"),
  or is deprecated entirely after tools migrate. New consumers read
  the live channel; legacy consumers can keep reading the static
  field as long as it's stamped from the final live value.

**What's missing:**

Chat UIs today render tool-call chips with the tool's name and,
once the call settles, optionally the `ToolResults.summary` field
(populated by the tool author at result-emit time, e.g. grep's
"12 matches across 31 files"). That's only the *post*-completion
half. While the tool is running, the chip shows only the tool name
— the user has to expand the chip to see what's happening, or wait
for the tool to settle to learn anything.

Tools KNOW what they're doing — grep knows the search pattern, an
import knows the current row count, `bsp_compile` knows the
current target — but there's no framework-blessed surface for them
to drive a live, inline chip label across the execution arc.

Today's options are all wrong-shape:

- `ToolProgress` notices — these exist (and chat UIs render them
  as a status line beneath the chip header), but they're TRANSIENT
  log lines that flow by. Not a persistent tagline.
- `ToolResults.summary` — final-only. Tool can't update mid-flight.
- Server-emitted Tool-role text Messages — poisons agent context
  (the entire #181 / #182 / #189 family). Not viable.
- Per-tool hardcoded extraction in the chat UI (what Sage's tome
  currently does with `_inputSummary()` — a switch on tool name
  extracting fields like `filePath` / `pattern` / `query` from
  `inputJson`). Brittle: every UI re-implements the same switch,
  silently degrades when new tools ship or args shapes change,
  tool author can't surface mid-flight state at all.

### Proposed shape

```scala
trait TurnContext {
  // … existing fields (caller, conversation, chain, turnInput, sigil, etc.)

  /** Reactive per-invocation summary that the tool writes to across
    * its execution arc. Surfaced inline in chat UIs' tool-call chips
    * so users can see what's happening without expanding. Each
    * `summary.set(...)` emits a delta through the normal event stream,
    * targeting this turn's `ToolInvoke._id`.
    *
    * Default empty (chip shows just the tool name when the tool
    * doesn't opt in). Tools opt in by writing to it from inside
    * `executeTyped` / `execute`:
    *
    *   - `set` at the start of work with input-derived context
    *     ("Searching '<pattern>' in <path>")
    *   - `set` periodically as work progresses ("Searched 12 files,
    *     7 matches so far")
    *   - `set` at completion with the final result ("12 matches
    *     across 31 files") — supersedes the static
    *     [[ToolResults.summary]] field for tools that opt in.
    *
    * Available to ALL tools regardless of input-typing — lives on
    * `TurnContext`, not `Tool` / `TypedTool`, because per-invocation
    * UI state is invocation-level, not tool-shape-level. Slash
    * commands, workflow steps, anything else running through the
    * tool-execution pathway gets the same affordance. */
  def summary: Var[String]
}
```

### Lifecycle pattern (tool-author shape)

```scala
case object GrepTool extends TypedTool[GrepInput](…) {
  override def executeTyped(input: GrepInput, ctx: TurnContext): Stream[Event] = {
    ctx.summary.set(s"Searching '${input.pattern}'")
    Stream.eval(searchAsync(input)).flatMap { results =>
      ctx.summary.set(s"${results.matchCount} matches across ${results.fileCount} files")
      Stream.emits(buildResultEvents(results))
    }
  }
}
```

For long-running tools that want intermediate updates:

```scala
case object ClaudeStateImportTool extends TypedTool[ClaudeStateImportInput](…) {
  override def executeTyped(input: ClaudeStateImportInput, ctx: TurnContext): Stream[Event] = {
    ctx.summary.set(s"Importing session ${input.sessionId.take(8)}…")
    importEvents(input).chunkWith { state =>
      ctx.summary.set(s"Imported ${state.count} / ${state.total} events")
    }.map { _ =>
      ctx.summary.set(s"Imported ${state.total} events, ${state.memoryCount} memory files")
      // … final result event …
    }
  }
}
```

### Wire shape

Each `summary.set(...)` emits one of:
- A new `SummaryDelta(target: Id[Event], value: String,
  conversationId: Id[Conversation])` extending `Delta`, OR
- An extension to the existing `ToolDelta` shape with an optional
  `summary: Option[String]` field.

The former is cleaner (single-purpose delta, no overloading
existing semantics); the latter has slightly fewer event subtypes.
Either works; the cleanest pick is probably `SummaryDelta` since
`ToolDelta` already has lifecycle semantics (state transitions)
that a summary update doesn't share.

UI consumers subscribe and update the chip's tagline in real time.

### Composition with existing fields

- **`ToolResults.summary`** — the legacy static field. After tools
  opt in to `ctx.summary`, the framework auto-stamps
  `ToolResults.summary` from the LAST value of `ctx.summary`
  observed during execution. Legacy consumers that read
  `ToolResults.summary` keep working without modification.
  Eventually the static field can be deprecated.
- **`ToolProgress` notices** — these stay distinct: transient log
  lines that the UI renders as scrolling progress, separate from
  the chip's persistent tagline. A tool can use both (push log
  entries via `ToolProgress`, update tagline via `ctx.summary`).
- **`ToolInvoke.callId`** — unchanged; the SummaryDelta targets
  `ToolInvoke._id`, same pattern other Deltas use.

### What this closes / supersedes

- Sage's tome currently ships a hardcoded `_inputSummary()` switch
  in `tome/lib/chat/widgets/tool_call_chip.dart` that extracts
  per-tool fields (`filePath` for read/edit, `pattern` for grep,
  `keywords` for find_capability, ~17 more) from `inputJson` to
  show inline chip detail. The user explicitly flagged this as
  wrong-layer architecture: tool authors know the meaningful
  summary, the UI is guessing. Once this Sigil enhancement lands
  and tools opt in, that switch becomes obsolete — tome's chip
  reads `invoke.summary` from the wire directly, no per-tool
  knowledge baked into the UI.
- Multiple "tool finished without telling the user anything
  useful" reports tracing back to result-only summary having no
  live counterpart. Long-running tools with no progress signal
  feel like the agent is hung; this gives every tool a way to say
  "I'm working, here's what I'm on right now."
- Composes with bug #190's corruption-resistance work: every tool
  that opts in to live summary updates gives the framework
  additional signal about which path the tool's executeTyped is
  on, which can inform recovery / timeout / retry decisions if
  needed.

### Test sketch

Under `core/src/test/scala/sigil/tool/`:

1. Define a fake tool whose `executeTyped` calls `ctx.summary.set`
   three times: at start, mid-stream, at completion.
2. Run an agent turn that invokes the tool.
3. Assert the event stream contains three `SummaryDelta` events
   (or three `ToolDelta`s with summary updates) in order, each
   targeting the same `ToolInvoke._id`, with the values matching
   the tool's `set` calls.
4. Assert `ToolResults.summary` is stamped with the LAST value of
   `ctx.summary` at result-emit time.
5. Negative: tool that doesn't write to `ctx.summary` produces
   zero SummaryDelta events; `ToolResults.summary` is `None`.
6. Subscribe a fake `signalsFor(viewer)` listener and assert the
   summary delta events arrive at the viewer in the same order
   they were emitted.

### Related

- Bug #190 — corruption-resistance architecture. Composes: tools
  with a live summary surface additional signal about their
  progress, which the framework's invariant-check / retry layer
  can use to distinguish "tool is making progress" from "tool is
  hung."
- Sage's tome hardcoded `_inputSummary()` switch — direct
  consumer that goes away when this lands. See
  `tome/lib/chat/widgets/tool_call_chip.dart` in the Sage repo.
- `ToolProgress` notice — orthogonal; both keep their distinct
  shapes.
