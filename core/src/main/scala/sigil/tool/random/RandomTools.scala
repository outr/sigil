package sigil.tool.random

import sigil.tool.Tool

/**
 * Convenience bundle for apps that want the entire random-tool family
 * registered. Apps add `RandomTools.all` to their `staticTools`
 * override:
 *
 * {{{
 *   override def staticTools: List[Tool] = super.staticTools ++ RandomTools.all
 * }}}
 *
 * Or pick the subset they want by referencing individual tools
 * directly. None are registered by default — agents that don't need
 * randomness shouldn't be tempted to reach for it on a coin-flip
 * shape question.
 */
object RandomTools {
  val all: List[Tool] = List(
    RandomIntTool,
    RandomDoubleTool,
    RandomChoiceTool,
    RandomUuidTool
  )
}
