package sigil.tool.fs

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.tool.model.GlobInput
import sigil.tool.output.{Node, PaginatedTool}
import sigil.tool.{ToolExample, ToolName}

/**
 * List files under `basePath` matching a glob pattern. Paginated:
 * top-level nodes are [[GlobEntry]] (one per file). The first page
 * is inline; the rest paginate via `next_page`.
 */
final class GlobTool(context: FileSystemContext)
  extends PaginatedTool[GlobInput, GlobEntry](
    name = ToolName("glob"),
    description0 = "List files under a directory matching a glob pattern (e.g. '**/*.scala').",
    examples = List(
      ToolExample("Scala sources under src", GlobInput(basePath = "src", pattern = "**/*.scala")),
      ToolExample("Top-level docs", GlobInput(basePath = ".", pattern = "*.md"))
    ),
    keywords = Set(
      "glob",
      "find",
      "list",
      "files",
      "pattern",
      "directory",
      "tree",
      "match",
      "wildcard",
      "path",
      "discover",
      "ls",
      "look",
      "browse",
      "enumerate"
    )
  )
  with sigil.tool.ReadOnlyExternalTool {
  // Bug #86 — generic primitive: ranks below domain-specific
  // tools when both match a query.
  override def preferIfNoBetter: Boolean = true

  override protected def executeStream(input: GlobInput, ctx: TurnContext): Stream[Node[GlobEntry]] =
    Stream.force(
      WorkspacePathResolver.resolve(ctx, input.basePath).flatMap { base =>
        context.listFiles(base, input.pattern, input.maxResults).map { paths =>
          Stream.fromIterator(Task.pure(paths.iterator.map(p => Node.leaf(GlobEntry(p)))))
        }
      }
    )
}
