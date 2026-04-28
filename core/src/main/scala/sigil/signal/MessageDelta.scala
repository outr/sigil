package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.provider.TokenUsage
import sigil.tool.model.{MultipartParser, ResponseContent}

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
                        usage: Option[TokenUsage] = None,
                        state: Option[EventState] = None)
  extends Delta derives RW {

  /**
   * Apply this delta to a [[sigil.event.Message]]. Per Option-A semantics:
   *   - `content` deltas are appended to `Message.content` only when
   *     `complete = true`. Streaming chunks (`complete = false`) are
   *     wire-only (for subscriber UX) and don't touch the persisted Message.
   *   - `usage` replaces.
   *   - `state` replaces.
   *
   * Returns `target` unchanged if it isn't a `Message`.
   */
  override def apply(target: Event): Event = target match {
    case m: Message =>
      val nextContent = content match {
        case Some(cd) if cd.complete => m.content :+ materialize(cd)
        case _ => m.content
      }
      val nextUsage = usage.getOrElse(m.usage)
      val nextState = state.getOrElse(m.state)
      m.copy(content = nextContent, usage = nextUsage, state = nextState)
    case other => other
  }

  private def materialize(cd: MessageContentDelta): ResponseContent = cd.kind match {
    case ContentKind.Text => ResponseContent.Text(cd.delta)
    case ContentKind.Markdown => ResponseContent.Markdown(cd.delta)
    case ContentKind.Code => ResponseContent.Code(cd.delta, cd.arg)
    case ContentKind.Heading => ResponseContent.Heading(cd.delta)
    case ContentKind.Divider => ResponseContent.Divider
    case ContentKind.Options =>
      MultipartParser.parseOptions(cd.delta).getOrElse(ResponseContent.Text(cd.delta))
    case ContentKind.Field =>
      MultipartParser.parseField(cd.delta).getOrElse(ResponseContent.Text(cd.delta))
    case _ => ResponseContent.Text(cd.delta)
  }
}
