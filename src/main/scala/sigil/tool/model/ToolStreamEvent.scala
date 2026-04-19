package sigil.tool.model

/**
 * Internal events produced by a streaming tool-call processor (e.g.
 * [[MultipartStreamParser]]). These are pipe-layer events consumed by the
 * provider accumulator, which translates them into `ProviderEvent`s on the
 * outbound stream.
 */
sealed trait ToolStreamEvent
object ToolStreamEvent {
  case class BlockStart(blockType: String, arg: Option[String]) extends ToolStreamEvent
  case class BlockDelta(text: String) extends ToolStreamEvent
}
