package sigil.tool

/**
 * A successful tool resolution as [[ToolExecutor]] settles it:
 * always the typed output, plus the overflow pointer when the
 * rendered form was bounded. Output bounding never replaces the
 * typed value — an [[ImageToolOutput]] with an oversized caption
 * keeps its image; a structured output stays readable by typed
 * consumers (workflow steps, app code) regardless of how large its
 * rendered form was.
 */
final case class ToolResultEnvelope[O <: ToolOutput](output: O, overflow: Option[OverflowPointer] = None)
