package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.provider.TokenUsage
import sigil.tool.model.ResponseContent

/**
 * A transient update to an active [[sigil.event.Message]]. Carries whatever
 * subset of mutations arrive together: content appending, usage accumulation,
 * state transition.
 *
 * The orchestrator applies a MessageDelta by (1) reading the target Message
 * from RocksDB, (2) mutating its content / usage / state fields per the
 * non-empty options, (3) writing the updated Message back, (4) broadcasting
 * the delta itself to subscribers so they can update their own views.
 *
 * **Subscriber contract — `content.complete` semantics:** every content
 * block produces TWO classes of deltas on the wire:
 *
 *   - `complete = false` — incremental chunks as the LLM streams the
 *     block. Useful for live "typing" UX.
 *   - `complete = true` — a single closing delta that carries the FULL
 *     accumulated text of the block (a snapshot, not a final chunk).
 *
 * Subscribers MUST pick one of two modes and stick to it for a given
 * subscription, or they double-render:
 *
 *   1. **Streaming UX** — append `delta` for every `complete = false`,
 *      and on `complete = true` either drop the block (if you've been
 *      accumulating) OR replace your accumulated text with the
 *      snapshot. Don't append the snapshot.
 *   2. **Snapshot-only** — ignore `complete = false`; render only the
 *      `complete = true` delta as the finalized block.
 *
 * The orchestrator-side persistence path uses snapshot-only — only
 * `complete = true` content deltas mutate the persisted `Message.content`.
 */
case class MessageDelta(target: Id[Event],
                        conversationId: Id[Conversation],
                        content: Option[MessageContentDelta] = None,
                        contentReplacement: Option[Vector[ResponseContent]] = None,
                        usage: Option[TokenUsage] = None,
                        state: Option[EventState] = None,
                        disposition: Option[sigil.event.MessageDisposition] = None,
                        /**
                         * Sigil #392 — set on the settle delta that commits a
                         * naked-text terminal answer: a turn that ended with
                         * `end_turn` + a user-visible text Message and NO tool
                         * call (the no-forced-tool_choice path — Fable/Mythos 5
                         * under the #387 self-heal). The agent loop treats it
                         * as a user-visible reply (like a `respond` settle), so
                         * the complete prose answer commits on the FIRST
                         * occurrence instead of being dropped and re-requested.
                         * Purely a loop signal; does not affect the projected
                         * `Message`.
                         */
                        terminalReply: Boolean = false)
  extends Delta derives RW {

  /**
   * Apply this delta to a [[sigil.event.Message]]:
   *   - `contentReplacement`, when set, replaces `Message.content` wholesale.
   *     Used by the orchestrator at turn-settle to swap the live streaming
   *     placeholder for the parsed markdown block sequence.
   *   - Otherwise, `content` deltas are appended to `Message.content` only
   *     when `complete = true`. Streaming chunks (`complete = false`) are
   *     wire-only (for subscriber UX) and don't touch the persisted Message.
   *   - `usage` ACCUMULATES (sigil #381). A turn makes one provider call
   *     per loop iteration; for the tool-calling iterations the usage
   *     lands on the same `lastUserVisibleMessageId`, so it must sum, not
   *     clobber — replacing billed the whole turn at only its last call's
   *     tokens (cost undercounted ~40×). Only AUTHORITATIVE usage
   *     accumulates; mid-stream estimates (`isEstimated`, OpenAI-compat
   *     wire) are wire-only for live UI tickers and never touch the
   *     persisted total.
   *   - `state` replaces.
   *
   * Returns `target` unchanged if it isn't a `Message`.
   */
  override def apply(target: Event): Event = target match {
    case m: Message =>
      val nextContent = contentReplacement match {
        case Some(blocks) => blocks
        case None =>
          content match {
            case Some(cd) if cd.complete => m.content :+ materialize(cd)
            case _ => m.content
          }
      }
      val nextUsage = usage match {
        case Some(u) if !u.isEstimated => m.usage + u
        case _ => m.usage
      }
      val nextState = state.getOrElse(m.state)
      val nextDisposition = disposition.getOrElse(m.disposition)
      m.copy(content = nextContent, usage = nextUsage, state = nextState, disposition = nextDisposition)
    case other => other
  }

  private def materialize(cd: MessageContentDelta): ResponseContent = cd.kind match {
    case ContentKind.Text => ResponseContent.Text(cd.delta)
    case ContentKind.Markdown => ResponseContent.Markdown(cd.delta)
    case ContentKind.Code => ResponseContent.Code(cd.delta, cd.arg)
    case ContentKind.Heading => ResponseContent.Heading(cd.delta)
    case ContentKind.Divider => ResponseContent.Divider
    case _ => ResponseContent.Text(cd.delta)
  }
}
