package sigil.tool.fs

import fabric.{Arr, Json, num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.model.GrepInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Search files under a path for a regex pattern. `glob`
 * (optional) restricts the file set; `contextLines` adds before/
 * after context to each match. Result event carries an array of
 * matches with file path, line number, content, and context.
 */
final class GrepTool(context: FileSystemContext)
  extends TypedTool[GrepInput](
    name = ToolName("grep"),
    description =
      """Search files under `path` for a regex pattern. `glob` optionally restricts the file set;
        |`contextLines` adds surrounding-context output to each match. Returns matching lines with file
        |path and line number.""".stripMargin,
    examples = List(
      ToolExample("Find TODOs in Scala source", GrepInput(path = "src", pattern = "TODO", glob = Some("**/*.scala"))),
      ToolExample("Find function definition with context", GrepInput(path = ".", pattern = "def myFunction", contextLines = 2))
    ),
    keywords = Set("grep", "search", "regex", "find", "match", "lines")
  ) {
  override protected def executeTyped(input: GrepInput, ctx: TurnContext): Stream[Event] = Stream.force(
    context.searchFiles(input.path, input.pattern, input.glob, input.maxMatches, input.contextLines).map { matches =>
      val items: Vector[Json] = matches.toVector.map { m =>
        obj(
          "filePath"      -> str(m.filePath),
          "lineNumber"    -> num(m.lineNumber),
          "content"       -> str(m.content),
          "contextBefore" -> Arr(m.contextBefore.map(str(_)).toVector),
          "contextAfter"  -> Arr(m.contextAfter.map(str(_)).toVector)
        )
      }
      val payload = obj("matches" -> Arr(items), "count" -> num(matches.size))
      Stream.emit[Event](FsToolEmit(payload, ctx))
    }
  )
}
