package sigil.tool.fs

import fabric.{Json, num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.storage.FileVersion
import sigil.tool.model.ReadFileInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Read a file from the [[FileSystemContext]]. Optional offset/limit
 * truncate the read window — useful for large files or paged
 * reading. The result is emitted as a `MessageRole.Tool` Message containing
 * a JSON object with the file contents and line metadata, so the
 * agent's next turn sees the structured payload.
 *
 * Apps that want sandboxing pass a `LocalFileSystemContext(basePath)`;
 * remote execution wraps this tool in
 * [[sigil.tool.proxy.ProxyTool]].
 */
final class ReadFileTool(context: FileSystemContext)
  extends TypedTool[ReadFileInput](
    name = ToolName("read_file"),
    description =
      """Read the contents of a file. Use `offset` (0-indexed line) and `limit` to read a window of large files.
        |Returns a JSON object with `content`, `totalLines`, and `linesRead`.""".stripMargin,
    examples = List(
      ToolExample("Read entire file", ReadFileInput(filePath = "README.md")),
      ToolExample("Read first 100 lines", ReadFileInput(filePath = "data.log", limit = Some(100))),
      ToolExample("Read lines 200-300", ReadFileInput(filePath = "data.log", offset = Some(200), limit = Some(100)))
    ),
    keywords = Set("file", "read", "open", "cat", "view")
  ) {
  override protected def executeTyped(input: ReadFileInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      WorkspacePathResolver.resolve(ctx, input.filePath).flatMap(operate(input, _))
        .map(json => Stream.emit[Event](FsToolEmit(json, ctx)))
    )

  private def operate(input: ReadFileInput, resolved: String): rapid.Task[Json] = (input.offset, input.limit) match {
    case (None, None) =>
      context.readFile(resolved).map { content =>
        val lines = content.split('\n').length
        obj(
          "content" -> str(content),
          "totalLines" -> num(lines),
          "linesRead" -> num(lines),
          "hash" -> str(FileVersion.hashOf(content))
        )
      }
    case (off, lim) =>
      context.readFileLines(resolved, off.getOrElse(0), lim.getOrElse(Int.MaxValue)).map {
        case (lines, total) =>
          // No `hash` on partial reads — the hash represents the
          // full file's bytes; passing it as `expectedHash` after a
          // windowed read would silently mismatch on commit.
          obj("content" -> str(lines.mkString("\n")), "totalLines" -> num(total), "linesRead" -> num(lines.size))
      }
  }
}
