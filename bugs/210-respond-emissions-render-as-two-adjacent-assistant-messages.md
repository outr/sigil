# ❌ #210 — `respond` (and respond-family) emissions render as TWO adjacent assistant messages in the prompt — once as plain text, once as the tool_call — doubling per-call context cost and reinforcing respond-loop patterns

**Where:**
- `core/src/main/scala/sigil/tool/core/RespondTool.scala:81-96` —
  `RespondTool.executeTyped` emits a `Message` event carrying the
  reply text. The framework orchestrator separately emits a
  `ToolInvoke(respond, args=...)` for the wire-level tool call.
- `core/src/main/scala/sigil/conversation/FrameBuilder.scala` —
  the renderer converts each event into a separate `ContextFrame`:
  - `Message` → `ContextFrame.Text` → assistant text message in
    the OpenAI/Anthropic-shaped prompt
  - `ToolInvoke` → `ContextFrame.ToolCall` → assistant tool_call
    message
- The two frames become two ADJACENT assistant messages in the
  rendered prompt, both carrying the same `content` value (one
  raw, one wrapped in the tool_call args JSON).

**What's wrong:**

The OpenAI/Anthropic chat protocols permit a SINGLE assistant
message to carry both `content` and `tool_calls`:

```json
{ "role": "assistant",
  "content": "Here's what I'm doing…",
  "tool_calls": [ { "function": { "name": "respond", "arguments": "{\"content\":\"Here's what I'm doing…\",…}" } } ] }
```

But Sigil's frame builder emits them as two distinct messages
because they came from two distinct events. Result: every
respond emission appears TWICE in the next iteration's prompt:

```json
{ "role": "assistant", "content": "Here's what I'm doing…" },
{ "role": "assistant", "tool_calls": [ { … "arguments": "{\"content\":\"Here's what I'm doing…\",…}" } ] }
```

Two problems with this:

1. **Per-call context cost is roughly doubled** for every respond.
   The `content` field is the largest part of a respond's payload
   (the rest is `topicLabel`, `topicSummary`, `disposition`,
   `endsTurn`, `keywords` — all small). Duplicating it across two
   adjacent messages means we're paying for the same text twice in
   every prompt the agent sees after every respond. For an agent
   that respond's many times in a multi-turn session, that
   compounds — at 15 responds of ~200 chars each, that's ~3000
   wasted tokens of duplicated context per subsequent iteration.

2. **It reinforces a respond-loop pattern that the system prompt
   tries to forbid.** The model sees a recent context that reads
   as a self-narrating loop:

   ```
   [n]   "I said X."                          ← Message text
   [n+1] tool_call: respond({content: X, …}) ← ToolInvoke
   [n+2] tool_result: ""                      ← respond's empty result
   [n+3] "I said Y."
   [n+4] tool_call: respond({content: Y, …})
   [n+5] tool_result: ""
   …
   ```

   `Instructions.scala:166-177` says explicitly:
   > "your job is to find it, NOT to fake it through `respond`"
   > "A refusal not preceded by find_capability is a bug"

   But the prompt-as-rendered shows the agent doing exactly that,
   over and over. After N iterations of "I said X. I called
   respond(X). Empty result." in the recent context, the
   single sentence at the top of the system prompt has
   proportionally less weight than N copies of the model's own
   recent pattern. The framework is *teaching* the loop —
   pattern-matching the most recent prompt content is what models
   do.

**Field repro** — Sage session, the same loop discussed in
bug #209. Direct excerpt from `sage-wire.jsonl` at 18:50:52, the
tools-narrowed iteration after the projection wipe:

```
[43] role=assistant content="The grep results show file-level
                             matches but I need to see the actual
                             line contents. Let me expand one of the
                             file nodes to see the specific matching
                             lines. I'll start with `Sigil.scala`
                             since it has the most matches (55)."

[44] role=assistant tool_call=respond({
       "topicLabel":"Bug Comment Cleanup",
       "topicSummary":"Searching for bug-related comments in Scala code",
       "content":"The grep results show file-level matches but I need
                  to see the actual line contents. Let me expand one
                  of the file nodes to see the specific matching
                  lines. I'll start with `Sigil.scala` since it has
                  the most matches (55).",
       "disposition":"Success",
       "endsTurn":false,
       "keywords":["grep","sigil","scala"]
     })
[45] role=tool content=""

[46] role=assistant content="I need to expand a file node to see
                             the actual matching lines. Let me try
                             to view the content of `Sigil.scala`
                             directly to understand the pattern of
                             bug comments."

[47] role=assistant tool_call=respond({
       "topicLabel":"Bug Comment Cleanup",
       "topicSummary":"Searching for bug-related comments in Scala code",
       "content":"I need to expand a file node to see the actual
                  matching lines. Let me try to view the content of
                  `Sigil.scala` directly to understand the pattern
                  of bug comments.",
       "disposition":"Success",
       "endsTurn":false,
       "keywords":["view","file","source","sigil"]
     })
[48] role=tool content=""
```

The `content` field of each respond appears verbatim in BOTH the
plain assistant text message (43, 46) AND the assistant tool_call
message (44, 47). That's the duplication.

This isn't a small-model failure (per the user's reminder: check
the request before blaming the model). The qwen-on-Low agent
faithfully continued the dominant pattern in its recent context:
"respond, respond, respond." The framework was sending it 15+
copies of that pattern.

**Suggested fix:**

Collapse `ToolInvoke(respond) + Message(respond.content)` (and
the parallel `respond_options` / `respond_field` / `respond_failure`
/ `no_response` / `respond_card` / `respond_cards` family — every
user-visible terminal tool in `Orchestrator.UserVisibleTerminalTools`)
into a SINGLE rendered assistant message in the prompt.

Two paths:

**Path 1 — fix at the frame-builder render layer (preferred).**
When the frame builder is rendering frames into the wire
`messages` array (the conversion from `Vector[ContextFrame]` to
provider-specific message shape), detect the pattern:

```
ContextFrame.ToolCall(toolName=respond family, ...)
ContextFrame.Text(content matches the respond's content arg, sourceEvent.origin == prior ToolInvoke._id)
```

…and merge the two into a single OpenAI/Anthropic assistant
message with both `content` and `tool_calls` populated. The
`Text` frame's content is what the user saw; the `ToolCall`
frame is the wire-level call. They MUST stay paired downstream
of this merge — anything that reads either of them should see
the same logical assistant action.

This keeps the underlying event model intact (Tool emits
Message + ToolInvoke fires separately — both record-keeping is
correct) but fixes the rendered prompt.

**Path 2 — don't emit the Message at all for respond-family.**
`RespondTool.executeTyped` would stop emitting a `Message` event;
the `respond`'s `content` argument becomes the canonical
user-visible reply via a different surface (client-side: extract
from the `ToolInvoke.input.content` field; UI: already does this
for chat rendering). This removes the duplication at the source
but is more invasive — multiple downstream consumers
(memory extractor, summarizer, conversation history dumps, etc.)
read `Message` events to find "what the agent said." All of them
would need to switch to reading `ToolInvoke(respond).input.content`.

Path 1 is cleaner: the event model stays observable as-is, only
the prompt-to-provider rendering changes.

**Knock-on effects to verify:**

- **#48** ("discovery-first as the framework's CORE ideology")
  enforcement becomes more effective. With responds taking half
  the context space they currently do, the "don't fake through
  respond" instruction has proportionally more weight in the
  rendered prompt.
- **The loop pattern in #209's field repro** ("I keep responding
  about wanting to do X") may resolve on its own without #210's
  framework-side stall detection becoming necessary. With one
  representation per respond, the model's recent context isn't
  reinforcing the pattern.
- **Per-iteration token cost** drops measurably on respond-heavy
  agents — Sage's typical session would shed maybe 1-3 KB of
  duplicated content per iteration, depending on respond
  frequency.

**Write the failing test FIRST** (same discipline as #209): land
a regression test under
`core/src/test/scala/spec/RespondFrameRenderingSpec.scala` that:

1. Constructs a `ToolInvoke(respond, args=...)` event + the
   `Message` event emitted by `RespondTool.executeTyped` against
   that ToolInvoke.
2. Runs the frame-build → wire-render path.
3. Asserts the resulting `messages` array contains exactly ONE
   assistant message for the respond, with both `content` and
   `tool_calls` populated, NOT two adjacent assistant messages.

The test should FAIL on current SNAPSHOT9 (proving the bug is
real) and PASS after the fix.

**Related:**
- **#48** — "don't fake it through respond" instruction in the
  system prompt; this bug undermines it by making faked-through-
  respond patterns dominate the recent context the model reads.
- **#209** — the field repro that surfaced this. Once tools were
  wiped (root cause), the agent fell into respond-loop. The
  duplication described here is what made the loop self-
  perpetuating instead of self-correcting.
- **`Orchestrator.UserVisibleTerminalTools`** — the set of tools
  this fix needs to apply to. Verify all members emit a
  `Message` + `ToolInvoke` pair so the duplication symptom is
  consistent across the family.

Small frame-builder change (a few lines in the render path) + a
unit test; observable improvement on every respond-heavy session.
