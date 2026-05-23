# ❌ #261 — Anthropic conversion orphans a `tool_use` when a user message arrives mid-tool

**Where:**

- `core/src/main/scala/sigil/provider/anthropic/AnthropicProvider.scala` — the
  events → Anthropic `messages` conversion.

**What's wrong:**

If a user types a message while an agent's tool call is in flight, the events
land in chronological order — `ToolInvoke` → `UserMessage` → `ToolResults` —
and the Anthropic conversion renders them straight through, producing:

```
msg N   [assistant]: tool_use (id=X)
msg N+1 [user]:      text … (the interleaved user message)
msg N+2 [user]:      tool_result (id=X)
```

Anthropic rejects this with:

```
HTTP 400 invalid_request_error — messages.N: `tool_use` ids were found without
`tool_result` blocks immediately after: X. Each `tool_use` block must have a
corresponding `tool_result` block in the next message.
```

The conversation is then stuck — every subsequent turn replays the broken
history and 400s again.

Wire-log evidence (Claude Haiku 4.5, fresh DB, real-time chat):

```
msg 10 [assistant]: tool_use(browser_screenshot, id=…zv3Vmk)
msg 11 [user]:      text  "Can you replace the main theme with this ingredient page?"
msg 12 [user]:      tool_result(browser_screenshot, id=…zv3Vmk)
```

**Minimal repro:** during any agent turn that calls a tool taking >0s, post a
follow-up user message before the tool returns. The next request to Anthropic
400s.

**Suggested fix:** in the Anthropic conversion, when an assistant message
ends with one or more `tool_use` blocks, the immediately-following user
message must begin with the matching `tool_result` blocks. Any interleaved
user `text` events go *after* the `tool_result` blocks in that same user
message (Anthropic allows mixed `tool_result` + `text` content blocks in one
user message), or in a separate user message that follows. The conversion
should reorder events to satisfy this — the underlying event log can stay
in chronological order; only the wire representation needs to be repacked.

A conservative implementation: when walking events, buffer any user-text /
assistant-text events that appear while there is an open
`tool_use → tool_result` pair; emit them once the pair is closed (i.e. after
the user message carrying the `tool_result`).

**Severity:** High — this is a basic real-time-chat scenario, and a single
mistimed message makes the conversation permanently unusable on Anthropic.
