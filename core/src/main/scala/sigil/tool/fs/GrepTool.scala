package sigil.tool.fs

import rapid.Task
import sigil.TurnContext
import sigil.tool.model.{GrepInput, GrepOutput}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * Search files under a path for a regex pattern. `glob` (optional)
 * restricts the file set; `contextLines` adds before/after context.
 * Emits a typed [[GrepOutput]] carrying the structured match list
 * — agents iterate `matches` and pattern-match on `filePath` /
 * `lineNumber` without parsing JSON.
 */
final class GrepTool(context: FileSystemContext)
  extends TypedOutputTool[GrepInput, GrepOutput](
    name = ToolName("grep"),
    description =
      """Search files under `path` for a regex pattern. `glob` optionally restricts the file set;
        |`contextLines` adds surrounding-context output to each match. Returns
        |`{matches: [{filePath, lineNumber, content, contextBefore, contextAfter}], count}`.""".stripMargin,
    examples = List(
      ToolExample("Find TODOs in Scala source", GrepInput(path = "src", pattern = "TODO", glob = Some("**/*.scala"))),
      ToolExample("Find function definition with context", GrepInput(path = ".", pattern = "def myFunction", contextLines = 2))
    ),
    keywords = Set("grep", "search", "regex", "find", "match", "lines")
  ) {
  // Bug #86 — generic primitive: ranks below domain-specific tools
  // (LSP/BSP, typed inspectors) when both match a query, but stays
  // findable when nothing more specific applies.
  override def preferIfNoBetter: Boolean = true

  override protected def executeTyped(input: GrepInput, ctx: TurnContext): Task[GrepOutput] =
    WorkspacePathResolver.resolve(ctx, input.path).flatMap { base =>
      context.searchFiles(base, input.pattern, input.glob, input.maxMatches, input.contextLines).map { matches =>
        GrepOutput(matches = matches.toList, count = matches.size)
      }
    }

  override protected def summarize(out: GrepOutput, jsonRendered: String): String =
    s"[grep ${out.count} match(es) — externalized; call tool_output_get to read]"
}
