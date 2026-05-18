package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * Live reasoning text from the agent's pre-content thinking phase.
 *
 * Sigil's wire decoders translate provider-emitted `reasoning_content`
 * deltas (OpenAI Responses, Anthropic extended thinking, DeepSeek,
 * llama.cpp grammar-constrained reasoning models, Kimi with
 * `reasoning_mode=on`) into `ProviderEvent.ThinkingDelta`. The
 * orchestrator forwards each one to consumers as a `ThinkingChunk`
 * so a UI can render a live tail of the agent's reasoning while the
 * turn is in flight.
 *
 * Notice (not Event) — transient by nature, no DB persistence, no
 * replay. Reconnecting clients don't see prior thinking; they see
 * the settled Message that materialized from the turn.
 *
 * `target` identifies the in-flight Message the thinking belongs
 * to — the same id the eventual Message will carry once the first
 * user-visible content delta lands. Consumers fuse a thinking-tail
 * UI with the settled Message via this id (the placeholder bubble
 * collapses into the real message when the matching Message arrives).
 *
 * `delta` is the raw text chunk as received from the provider — one
 * ThinkingChunk per `ThinkingDelta`; chunks are not coalesced.
 */
case class ThinkingChunk(target: Id[Event],
                         conversationId: Id[Conversation],
                         delta: String) extends Notice derives RW
