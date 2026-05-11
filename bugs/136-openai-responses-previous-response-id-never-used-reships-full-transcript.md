# ❌ #136 — OpenAI Responses provider never sets `previous_response_id`; every turn reships the full transcript

**Where:**
- `core/src/main/scala/sigil/provider/openai/OpenAIProvider.scala:139-148` — `buildBody` constructs the Responses API request. The `baseFields` vector includes `"input"` and `"stream"` but never `"previous_response_id"`.
- `core/src/main/scala/sigil/provider/openai/OpenAIProvider.scala` (response decode path) — the SSE `response.created` event carries the new response's `id`, but it isn't captured / persisted for the next turn to reference.

**What's wrong:**

The OpenAI Responses API supports server-side conversation state via `previous_response_id` — passing the prior response's `id` on a follow-up request tells OpenAI to keep the previous turn's input + output server-side, and you only pay for the new delta input. Sigil never uses this. Every turn reships the entire conversation history (system prompt + every prior `function_call`, `function_call_output`, `reasoning`, and `output_text` item).

**Real cost evidence** (Sage session, 2026-05-11, 43 OpenAI Responses calls):

- 0/43 calls set `previous_response_id`.
- By turn 42, the request body's `input` array contained 175KB of accumulated history. Most of that was stale (early-turn LSP results, exploration find_capability outputs, etc.).
- Cache-hit rate stayed at 38.9% (the provider's prefix-cache catching some of the static system-prompt-and-tools prefix, but not the conversation tail).
- Total spend on the session: **$1.057** (Sigil's `ConversationCostUpdated` total). Estimated savings with `previous_response_id`: **$0.25-$0.35 per session** (cache-hit rate jumps to ~95% because every turn only ships the new user message + the prior turn's tool output instead of the whole accumulated transcript).

For longer sessions (8h coding workflow, hundreds of turns), the savings scale super-linearly because transcript size grows quadratically without compaction. A 4-hour heavy session that costs ~$8 today would cost roughly $1-$2 with `previous_response_id`.

**Why this is structural, not just policy:**

The Responses API was designed around `previous_response_id` as the canonical stateful pattern. OpenAI documents it as the recommended approach: <https://platform.openai.com/docs/api-reference/responses/create#responses-create-previous_response_id>. Sigil's current `buildBody` essentially treats Responses as Chat-Completions-with-different-field-names — using the verbose stateless form. That trades all the API's intended efficiency for nothing.

**Test first:**

```scala
class OpenAIResponsesPreviousResponseIdSpec extends AsyncWordSpec with Matchers {
  "OpenAIProvider on a multi-turn conversation" should {
    "set `previous_response_id` on the second + subsequent turns" in {
      // Drive two consecutive provider calls with the same conversation
      // chain. Inspect the second call's request body. Assert
      // `previous_response_id` is populated with the first call's
      // response.id.
    }

    "omit prior conversation turns from the second-turn `input` array" in {
      // Same scenario. The second turn's `input` should contain ONLY
      // the new user message + the tool_output from the just-completed
      // function_call (when applicable). Earlier turns belong on
      // OpenAI's server-side state via previous_response_id.
    }

    "fall back to full-transcript input when previous_response_id is unavailable" in {
      // The first turn of a conversation has no prior response_id —
      // it must fall back to the current full-input shape. Same for
      // a session where the prior response_id expired or was lost.
    }
  }

  "Conversation projection" should {
    "persist the latest OpenAI response.id per (conversationId, agentId)" in {
      // The framework needs somewhere to store the response_id for
      // retrieval on the next turn. Verify the storage shape and
      // round-trip behavior.
    }
  }
}
```

All four must fail on current `main`.

**Suggested fix:**

Two cooperating changes:

### 1. Capture `response.id` from the SSE stream

In `OpenAIProvider`'s SSE decoder, when the `response.created` event arrives:

```jsonc
{"type": "response.created", "response": {"id": "resp_abc123...", "status": "in_progress", ...}}
```

Persist the `id` for this conversation/agent pair. Either on `ParticipantProjection` (alongside `recentTools` / `suggestedTools`) or in a new `ProviderState` per-conversation table. The id is short-lived (OpenAI rotates after ~30 days) so the storage doesn't need long-term durability — just live across consecutive turns within a session.

### 2. Wire `previous_response_id` into `buildBody`

```scala
private def buildBody(input: ProviderCall): Json = {
  val priorResponseId = input.providerState.previousResponseId  // new field
  val (effectiveMessages, baseFieldsExtras) = priorResponseId match {
    case Some(id) =>
      // Server-side state in play — `input` carries only the new
      // delta (latest user message + tool_output for any in-flight
      // function_call). Strip everything from before the prior
      // response's frontier.
      val delta = trimMessagesToDelta(input.messages, sinceResponseId = id)
      (delta, Vector("previous_response_id" -> str(id)))
    case None =>
      // First-of-conversation, or prior response_id expired. Full
      // input shape (today's path).
      (input.messages, Vector.empty)
  }
  val inputItems = renderInput(effectiveMessages)
  val baseFields = Vector[(String, Json)](
    "model" -> str(modelName),
    "input" -> arr(inputItems*),
    "stream" -> bool(true)
  ) ++ baseFieldsExtras
  // ... rest of buildBody unchanged ...
}
```

`trimMessagesToDelta` walks the messages list back from the end until it hits the message that corresponds to the prior `response.id`'s output. Everything strictly newer than that is shipped; everything older is on OpenAI's server side and assumed to be there.

### 3. Failure handling

OpenAI returns HTTP 400 with `error.code = "previous_response_not_found"` when the id has expired / been deleted. Catch this on retry:

```scala
case Failure(HttpException(400, body)) if body.contains("previous_response_not_found") =>
  // Drop the cached response_id, re-attempt the call with the full-
  // transcript fallback shape. Log at WARN so this regression of
  // server-side state is visible to operators.
  clearPreviousResponseId(input.conversationId, input.agentId).flatMap(_ =>
    call(input.copy(providerState = input.providerState.clearPreviousResponseId))
  )
```

This keeps the optimization correct under adverse conditions — an expired id triggers a one-time fallback rather than the conversation getting stuck.

**Composes cleanly with:**

- **#131 (MCP convention adoption)** — the `ToolResult` envelope shape gives the framework a clean place to know "this is a tool result that needs to be paired with the latest function_call_output in the next turn's delta."
- **Anthropic equivalent** — `AnthropicProvider` has a similar opportunity via Claude's Messages API system-prompt caching. Worth filing as a sibling bug after this one lands, since the same wire-transcript-bloat pattern hits Anthropic too.
- **Sage's per-conversation memory** (currently broken — Sage's own bug filed separately) — would also help reduce per-turn input size for the cases where `previous_response_id` isn't available.

**Bottom line**: this is the single biggest cost-savings opportunity on the table — ~30% reduction on a typical Sage session, scaling super-linearly with conversation length. Test-first via the spec above keeps the optimization correct under expiry, first-turn, and re-routing-to-different-model edge cases.
