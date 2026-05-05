package sigil.tool.output

import lightdb.id.Id
import rapid.Task
import sigil.TurnContext
import sigil.storage.StoredFile
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * `tool_output_search` — line-grep over an externalized tool-output
 * payload, returning each match plus optional context lines. Lets
 * the agent locate specific markers in a long compile log / large
 * grep result / workspace-symbols dump without loading the full
 * payload through `tool_output_get`.
 *
 * `pattern` is a Java regex (case-sensitive). Lines are split on
 * `\n`. `contextLines` (default 0) emits N lines before and after
 * each match. `maxMatches` (default 100) caps results so a wildly
 * matching pattern can't fill context with the whole payload.
 *
 * Emits a typed [[ToolOutputSearchResult]] enum.
 */
case object ToolOutputSearchTool
  extends TypedOutputTool[ToolOutputSearchInput, ToolOutputSearchResult](
    name = ToolName("tool_output_search"),
    description =
      """Search an externalized tool-output payload for lines matching a regex `pattern`.
        |Returns matches with optional context lines. Useful for finding specific markers
        |in a long compile log or large grep result without fetching the whole payload via
        |`tool_output_get`. `maxMatches` caps results (default 100).""".stripMargin,
    examples = List(
      ToolExample("Find every error in a compile log",
        ToolOutputSearchInput(outputId = "abc123", pattern = "(?i)error|failed", contextLines = Some(2))),
      ToolExample("Find usages of a symbol in workspace-symbols dump",
        ToolOutputSearchInput(outputId = "abc123", pattern = "MyClass\\b", maxMatches = Some(20)))
    ),
    keywords = Set("tool", "output", "search", "grep", "find")
  ) {

  override protected def executeTyped(input: ToolOutputSearchInput, ctx: TurnContext): Task[ToolOutputSearchResult] =
    ctx.sigil.fetchStoredFile(Id[StoredFile](input.outputId), ctx.chain).map {
      case None =>
        ToolOutputSearchResult.NotFound(
          outputId = input.outputId,
          error    = s"output ${input.outputId} not found or unauthorized"
        )
      case Some((_, bytes)) =>
        val text     = new String(bytes, "UTF-8")
        val lines    = text.split("\n", -1)
        val regex    = scala.util.Try(input.pattern.r).toOption
        val ctxLines = input.contextLines.getOrElse(0).max(0)
        val cap      = input.maxMatches.getOrElse(100).max(1)
        regex match {
          case None =>
            ToolOutputSearchResult.InvalidPattern(
              outputId = input.outputId,
              pattern  = input.pattern,
              error    = s"invalid regex: ${input.pattern}"
            )
          case Some(r) =>
            val matchedIndexes = lines.indices.filter(i => r.findFirstIn(lines(i)).isDefined).take(cap).toList
            val matches = matchedIndexes.map { i =>
              val from   = (i - ctxLines).max(0)
              val to     = (i + ctxLines + 1).min(lines.length)
              ToolOutputSearchHit(
                lineNumber = (i + 1).toLong,
                match_     = lines(i),
                context    = lines.slice(from, to).mkString("\n")
              )
            }
            ToolOutputSearchResult.Found(
              outputId   = input.outputId,
              pattern    = input.pattern,
              totalLines = lines.length.toLong,
              matches    = matches,
              truncated  = matchedIndexes.size == cap
            )
        }
    }
}
