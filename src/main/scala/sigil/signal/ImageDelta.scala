package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.tool.model.ResponseContent

/**
 * Replaces the `Image` content block on a target [[Message]]. Used by
 * the orchestrator to stream image-generation previews — each successive
 * `partial_image` from the provider becomes an `ImageDelta` carrying the
 * latest preview, and the final `image_generation.completed` event
 * applies the final image plus a `state = Complete` settle.
 *
 * Replace semantics, not append: the previous image is removed before
 * the new one is added. This matches how a UI renders a single
 * progressively-improving image rather than a chain of snapshots.
 *
 * Non-image content (text, options, etc.) on the target Message is
 * preserved.
 */
case class ImageDelta(target: Id[Event],
                     conversationId: Id[Conversation],
                     url: spice.net.URL,
                     altText: Option[String] = None)
  extends Delta derives RW {

  override def apply(target: Event): Event = target match {
    case m: Message =>
      val withoutImage = m.content.filterNot {
        case _: ResponseContent.Image => true
        case _ => false
      }
      m.copy(content = withoutImage :+ ResponseContent.Image(url = url, altText = altText))
    case other => other
  }
}
