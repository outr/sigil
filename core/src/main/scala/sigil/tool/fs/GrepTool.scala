package sigil.tool.fs

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.tool.model.GrepInput
import sigil.tool.output.{Node, PaginatedTool}
import sigil.tool.{ToolExample, ToolName}

/**
 * Search files under a path for a regex pattern. Tree-shaped
 * paginated output:
 *   - top-level nodes are [[GrepNode.FileMatch]] records (one
 *     per file with at least one hit)
 *   - each file node carries `hasChildren = true`; expanding it
 *     via `next_page` returns its [[GrepNode.LineMatch]] children
 *
 * Replaces the prior typed-blob shape — bulk grep no longer
 * externalizes to a per-call pointer that the agent has to
 * navigate via `tool_output_get`. The first page of files lands
 * inline; the rest paginate via [[sigil.tool.output.NextPageTool]].
 */
final class GrepTool(context: FileSystemContext)
  extends PaginatedTool[GrepInput, GrepNode](
    name = ToolName("grep"),
    description0 =
      """Search files under `path` for a regex pattern. An optional file-set glob restricts the
      |search; `contextLines` adds surrounding-context lines to each match.
      |
      |Returns a paginated tree: the top-level page lists files (one node per file with
      |at least one match, with `matchCount`); children are the matching lines for that
      |file.""".stripMargin,
    examples = List(
      ToolExample("Find TODOs in Scala source", GrepInput(path = "src", pattern = "TODO", glob = Some("**/*.scala"))),
      ToolExample("Find function definition with context", GrepInput(path = ".", pattern = "def myFunction", contextLines = 2))
    ),
    keywords = Set(
      "grep",
      "search",
      "regex",
      "find",
      "match",
      "lines",
      "lookup",
      "ripgrep",
      "rg",
      "code",
      "text",
      "files",
      "pattern",
      "scan",
      "look",
      "occurrence",
      "string"
    )
  )
  with sigil.tool.ReadOnlyExternalTool {
  // Bug #86 — generic primitive: ranks below domain-specific tools
  // (LSP/BSP, typed inspectors) when both match a query, but stays
  // findable when nothing more specific applies.
  override def preferIfNoBetter: Boolean = true

  override protected def executeStream(input: GrepInput, ctx: TurnContext): Stream[Node[GrepNode]] =
    Stream.force(
      WorkspacePathResolver.resolve(ctx, input.path).flatMap { base =>
        context.searchFiles(base, input.pattern, input.glob, input.maxMatches, input.contextLines).map { matches =>
          // Group by file. Each file becomes a top-level Node with
          // its line-matches as child Nodes (lazy children stream
          // built from the grouped list).
          val byFile = matches.groupBy(_.filePath).toList.sortBy(_._1)
          Stream.fromIterator(Task.pure(byFile.iterator.map { case (filePath, fileMatches) =>
            val children = Stream.fromIterator(Task.pure(
              fileMatches.iterator.map { m =>
                Node.leaf[GrepNode](GrepNode.LineMatch(
                  lineNumber = m.lineNumber,
                  content = m.content,
                  contextBefore = m.contextBefore,
                  contextAfter = m.contextAfter
                ))
              }
            ))
            Node.parent[GrepNode](
              payload = GrepNode.FileMatch(filePath = filePath, matchCount = fileMatches.size),
              children = children
            )
          }))
        }
      }
    )
}
