package sigil.tool.fs

import fabric.{Arr, num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.model.GlobInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * List files under `basePath` matching a glob pattern.
 * Result event carries `paths: List[String]` and `count`.
 */
final class GlobTool(context: FileSystemContext)
  extends TypedTool[GlobInput](
    name = ToolName("glob"),
    description = "List files under a directory matching a glob pattern (e.g. '**/*.scala'). Returns relative paths.",
    examples = List(
      ToolExample("Scala sources under src", GlobInput(basePath = "src", pattern = "**/*.scala")),
      ToolExample("Top-level docs", GlobInput(basePath = ".", pattern = "*.md"))
    ),
    keywords = Set("glob", "find", "list", "files", "pattern")
  ) {
  override protected def executeTyped(input: GlobInput, ctx: TurnContext): Stream[Event] = Stream.force(
    context.listFiles(input.basePath, input.pattern, input.maxResults).map { paths =>
      val payload = obj("paths" -> Arr(paths.map(str(_)).toVector), "count" -> num(paths.size))
      Stream.emit[Event](FsToolEmit(payload, ctx))
    }
  )
}
