package sigil.tool.fs

import rapid.Task
import sigil.TurnContext
import sigil.tool.model.{GlobInput, GlobOutput}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * List files under `basePath` matching a glob pattern. Emits a
 * typed [[GlobOutput]] (`paths: List[String], count: Int`).
 */
final class GlobTool(context: FileSystemContext)
  extends TypedOutputTool[GlobInput, GlobOutput](
    name = ToolName("glob"),
    description = "List files under a directory matching a glob pattern (e.g. '**/*.scala'). Returns `{paths: [String], count}`.",
    examples = List(
      ToolExample("Scala sources under src", GlobInput(basePath = "src", pattern = "**/*.scala")),
      ToolExample("Top-level docs", GlobInput(basePath = ".", pattern = "*.md"))
    ),
    keywords = Set(
      "glob", "find", "list", "files", "pattern",
      "directory", "tree", "match", "wildcard", "path", "discover",
      "ls", "look", "browse", "enumerate"
    )
  ) {
  // Bug #86 — generic primitive: ranks below domain-specific
  // tools when both match a query.
  override def preferIfNoBetter: Boolean = true

  override protected def executeTyped(input: GlobInput, ctx: TurnContext): Task[GlobOutput] =
    WorkspacePathResolver.resolve(ctx, input.basePath).flatMap { base =>
      context.listFiles(base, input.pattern, input.maxResults).map { paths =>
        GlobOutput(paths = paths.toList, count = paths.size)
      }
    }
}
