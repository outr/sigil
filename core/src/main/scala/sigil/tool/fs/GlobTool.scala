package sigil.tool.fs

import fabric.rw.*
import rapid.Task
import sigil.tool.model.GlobInput
import sigil.tool.*

/**
 * List files under `basePath` matching a glob pattern. Returns the
 * matching paths, one per line, bounded by `maxResults` and landing
 * directly in the agent's context (the framework's overflow path
 * files a genuinely large listing). To search within the listing,
 * grep the paths it returned.
 */
final class GlobTool(context: FileSystemContext) extends Tool with sigil.tool.ReadOnlyExternalTool {
  type Input = GlobInput
  type Output = TextToolOutput
  override val examples: List[ToolExample] = List(
    ToolExample("Scala sources under src", GlobInput(basePath = "src", pattern = "**/*.scala")),
    ToolExample("Top-level docs", GlobInput(basePath = ".", pattern = "*.md"))
  )
  override val keywords: Set[String] = Set(
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
  val inputRW = summon[RW[GlobInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("glob")
  val description =
    "List files under a directory matching a glob pattern (e.g. '**/*.scala'). Returns the matching " +
      "paths, one per line, bounded by `maxResults`."

  override def preferIfNoBetter: Boolean = true

  override def executeResult(input: GlobInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    PlaceholderInputDetector.validateNoPlaceholders("basePath" -> input.basePath) match {
      case Some(reason) => Task.pure(ToolResult.failure(reason))
      case None =>
        WorkspacePathResolver.resolve(ctx, input.basePath).flatMap { base =>
          context.listFiles(base, input.pattern, input.maxResults).map { paths =>
            val text = if (paths.isEmpty) "(no matches)" else paths.mkString("\n")
            ToolResult.Success(TextToolOutput(text))
          }
        }
    }
}
