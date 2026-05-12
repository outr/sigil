# ❌ #157 — Unify the `respond_*` family into a single `respond` tool with content-kind discrimination

**Where:**
- `core/src/main/scala/sigil/tool/core/RespondTool.scala` — primary text/markdown terminal
- `core/src/main/scala/sigil/tool/core/RespondFailureTool.scala` — emits `ResponseContent.Failure`
- `core/src/main/scala/sigil/tool/core/RespondFieldTool.scala` — emits `ResponseContent.Field`
- `core/src/main/scala/sigil/tool/core/RespondOptionsTool.scala` — emits `ResponseContent.Options`
- `core/src/main/scala/sigil/tool/model/RespondInput.scala` + the three sibling inputs
- `core/src/main/scala/sigil/tool/core/CoreTools.scala` — all four registered

**What's wrong:**

The four `respond_*` tools are structural duplicates. Each one's `executeTyped` reduces to "build a `Message` with a `Vector(<one specific content-kind block>)` and emit it." The only reason they exist as separate tools is that `RespondTool`'s `content: String` field flows through `MarkdownContentParser.parse(...)`, which produces text / code blocks but has no path to emit `ResponseContent.Failure`, `ResponseContent.Field`, or `ResponseContent.Options`. So the framework added three parallel tools, one per content-kind, each wrapping the same shape.

Cost surface today (verified against a Sage wire log roster):

| Tool | chars |
|---|---|
| `respond` | 2,742 |
| `respond_options` | 2,877 |
| `respond_failure` | 372 |
| `respond_field` | 322 |
| **Total** | **6,313** |

Roughly 37% of a default tool roster goes to four variants of "end the turn with one content block." Every agent turn carries all of them in case the model picks the right one. The model also has to make a four-way decision about which kind of respond to use — when in most cases the content-kind is obvious from what the model wants to say.

Each variant's executeTyped body is ~10 lines, almost identical:

```scala
// RespondFailureTool
val block = ResponseContent.Failure(reason = input.reason, recoverable = input.recoverable)
rapid.Stream.emits(List(Message(content = Vector(block), ...)))

// RespondFieldTool
val block = ResponseContent.Field(...)
rapid.Stream.emits(List(Message(content = Vector(block), ...)))

// RespondOptionsTool
val block = ResponseContent.Options(...)
rapid.Stream.emits(List(Message(content = Vector(block), ...)))
```

The only thing varying is which `ResponseContent` subtype gets built. This is a textbook case for collapsing into one tool with a discriminator.

**Suggested fix:**

Single `respond` tool with content-kind discrimination on the input. Two reasonable shapes; pick whichever fits Sigil's input-schema conventions better:

**Option A — discriminator field:**

```scala
case class RespondInput(
  kind: RespondKind,                       // "text" | "failure" | "field" | "options"
  text: Option[String] = None,             // populated for kind=text/failure
  options: Option[List[OptionSpec]] = None, // kind=options
  field: Option[FieldSpec] = None,         // kind=field
  recoverable: Option[Boolean] = None,     // kind=failure
  topicLabel: Option[String] = None,
  topicSummary: Option[String] = None,
  keywords: Option[List[String]] = None,
  endsTurn: Boolean = true
)
```

**Option B — tagged union per kind:**

```scala
sealed trait RespondContent
case class TextContent(content: String) extends RespondContent
case class FailureContent(reason: String, recoverable: Boolean) extends RespondContent
case class FieldContent(label: String, value: String) extends RespondContent
case class OptionsContent(prompt: String, options: List[Option]) extends RespondContent

case class RespondInput(
  content: RespondContent,
  topicLabel: ...,
  ...
)
```

Option B is more type-safe and tracks Sigil's discriminator conventions (`ToolInput` is already polymorphic). Option A is closer to what providers natively render (most wire formats prefer flat schemas with discriminator fields for tool args).

In either shape, the executeTyped consolidates into one switch:

```scala
override protected def executeTyped(input: RespondInput, context: TurnContext): Stream[Event] = {
  val block = input match {
    case Text(c)               => MarkdownContentParser.parse(c)  // existing logic
    case Failure(r, recov)     => Vector(ResponseContent.Failure(r, recov))
    case Field(l, v)           => Vector(ResponseContent.Field(l, v))
    case Options(p, opts)      => Vector(ResponseContent.Options(p, opts))
  }
  Stream.emits(List(Message(content = block, ...)))
}
```

**Migration:**

- `RespondFailureTool` / `RespondFieldTool` / `RespondOptionsTool` deprecated, then removed in a follow-up
- `CoreTools.coreToolNames` shrinks accordingly
- `Instructions.scala` collapses the four-variant decision tree into one section about `respond` with content kinds
- Apps that pinned specific tool names (`pin_tool("respond_failure")`) get a deprecation warning + redirect to `respond`

**Savings:**

~3,571 chars per turn's roster (the three side-tools' combined size). On Sage's currently-trimmed roster of ~11.5 K chars, that's another ~30% reduction. The token-count threshold (#159's tool-roster-share insight at 25%) would stop firing on typical turns.

Beyond bytes: the agent's decision space shrinks. Today's four-way pick ("which respond variant?") becomes a single-tool call where the content-kind is part of the input — same model decision, cleaner shape, less per-call instruction overhead in the framework's prompt.

**Severity:** API hygiene + tool-roster bloat + agent-decision noise. Fine-functional today; the framework's tool-roster-share warning (`Tool roster is X% of context`) is already pointing at this redundancy as a real cost surface. The unification is mechanical and shrinks the default surface measurably for every Sigil consumer.
