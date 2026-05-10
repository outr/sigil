package sigil.tool.fs

import rapid.Task
import sigil.TurnContext
import sigil.storage.FileVersion
import sigil.tool.model.{ReadFileInput, ReadFileOutput}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * Read a file from the [[FileSystemContext]]. Optional offset/limit
 * truncate the read window — useful for large files or paged
 * reading. Emits a typed [[ReadFileOutput]] (`content`,
 * `totalLines`, `linesRead`, optional `hash` for unwindowed reads).
 *
 * Apps that want sandboxing pass a `LocalFileSystemContext(basePath)`;
 * remote execution wraps this tool in [[sigil.tool.proxy.ProxyTool]].
 */
final class ReadFileTool(context: FileSystemContext)
  extends TypedOutputTool[ReadFileInput, ReadFileOutput](
    name = ToolName("read_file"),
    description =
      """Read the contents of a file. Use `offset` (0-indexed line) and `limit` to read a window of large files.
        |Returns `{content, totalLines, linesRead, hash?}`. `hash` is populated only on unwindowed reads.""".stripMargin,
    examples = List(
      ToolExample("Read entire file", ReadFileInput(filePath = "README.md")),
      ToolExample("Read first 100 lines", ReadFileInput(filePath = "data.log", limit = Some(100))),
      ToolExample("Read lines 200-300", ReadFileInput(filePath = "data.log", offset = Some(200), limit = Some(100)))
    ),
    keywords = Set(
      "file", "read", "open", "cat", "view",
      "contents", "source", "examine", "inspect", "load", "show",
      "code", "text", "lines", "display", "fetch", "look"
    )
  ) {
  // Bug #86 — generic primitive: ranks below domain-specific
  // tools when both match a query.
  override def preferIfNoBetter: Boolean = true

  override protected def executeTyped(input: ReadFileInput, ctx: TurnContext): Task[ReadFileOutput] =
    WorkspacePathResolver.resolve(ctx, input.filePath).flatMap(operate(input, _))

  private def operate(input: ReadFileInput, resolved: String): Task[ReadFileOutput] = (input.offset, input.limit) match {
    case (None, None) =>
      context.readFile(resolved).map { content =>
        val lines = content.split('\n').length
        ReadFileOutput(
          content    = content,
          totalLines = lines,
          linesRead  = lines,
          hash       = Some(FileVersion.hashOf(content))
        )
      }
    case (off, lim) =>
      context.readFileLines(resolved, off.getOrElse(0), lim.getOrElse(Int.MaxValue)).map {
        case (lines, total) =>
          // No `hash` on partial reads — the hash represents the
          // full file's bytes; passing it as `expectedHash` after a
          // windowed read would silently mismatch on commit.
          ReadFileOutput(
            content    = lines.mkString("\n"),
            totalLines = total,
            linesRead  = lines.size,
            hash       = None
          )
      }
  }

  override protected def summarize(out: ReadFileOutput, jsonRendered: String): String =
    s"[read_file ${out.linesRead}/${out.totalLines} lines, ${out.content.length} chars — externalized; call tool_output_get to read]"
}
