package sigil.tool.fs

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.storage.FileVersion
import sigil.tool.model.{ReadFileInput, ReadFileOutput}
import sigil.tool.{PlaceholderInputDetector, Tool, ToolExample, ToolName, ToolResult}

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
  extends Tool with sigil.tool.ReadOnlyExternalTool {
  type Input  = ReadFileInput
  type Output = ReadFileOutput
  val inputRW  = summon[RW[ReadFileInput]]
  val outputRW = summon[RW[ReadFileOutput]]
  val name = ToolName("read_file")
  val description =
    """Read the contents of a file. Use `offset` (0-indexed line) and `limit` to read a window of large files.
      |Returns `{content, totalLines, linesRead, hash?}`. `hash` is populated only on unwindowed reads.""".stripMargin
  override val examples = List(
    ToolExample("Read entire file", ReadFileInput(filePath = "README.md")),
    ToolExample("Read first 100 lines", ReadFileInput(filePath = "data.log", limit = Some(100))),
    ToolExample("Read lines 200-300", ReadFileInput(filePath = "data.log", offset = Some(200), limit = Some(100)))
  )
  override val keywords = Set(
    "file", "read", "open", "cat", "view",
    "contents", "source", "examine", "inspect", "load", "show",
    "code", "text", "lines", "display", "fetch", "look"
  )

  // Generic primitive: ranks below domain-specific tools when both match a query.
  override def preferIfNoBetter: Boolean = true

  // read_file bounds its OWN output: a read whose content exceeds the inline
  // cap is truncated inline with offset/limit continuation guidance, never spilled
  // to another overflow file. This is what makes reading an externalized result
  // safe — the read-back can't cascade into yet another `.sigil/output` file
  // (sigil #370).
  override def boundsOutputItself: Boolean = true

  override def executeResult(input: ReadFileInput, ctx: ToolContext): Task[ToolResult[ReadFileOutput]] =
    PlaceholderInputDetector.validateNoPlaceholders("filePath" -> input.filePath) match {
      case Some(reason) => Task.pure(ToolResult.failure(message = reason))
      case None        =>
        WorkspacePathResolver.resolve(ctx, input.filePath)
          .flatMap(operate(input, _))
          .map(out => capInline(out, input, ctx.sigil.inlineContentThreshold))
          .map(ToolResult.success(_))
    }

  /** Cap an over-cap read to the inline limit at a line boundary, appending a
    * single consistent continuation note (`read_file` with `offset`/`limit`) —
    * so reading a large file (including an externalized overflow file) returns a
    * bounded, retrievable window instead of re-overflowing. */
  private def capInline(out: ReadFileOutput, input: ReadFileInput, threshold: Long): ReadFileOutput = {
    if (out.content.length.toLong <= threshold) out
    else {
      val keep       = out.content.take(threshold.toInt)
      val atBoundary = keep.lastIndexOf('\n') match {
        case -1 => keep
        case i  => keep.take(i)
      }
      val shown      = if (atBoundary.isEmpty) 1 else atBoundary.count(_ == '\n') + 1
      val baseOffset = input.offset.getOrElse(0)
      val note =
        s"\n\n[read_file: showing lines ${baseOffset + 1}-${baseOffset + shown} of ${out.totalLines} " +
          s"(${atBoundary.length}/${out.content.length} bytes; capped at the $threshold-byte inline limit). " +
          s"Call read_file again with offset=${baseOffset + shown} (and a limit) to read more.]"
      out.copy(content = atBoundary + note, linesRead = shown, hash = None)
    }
  }

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
}
