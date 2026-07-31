package sigil.tool.fs

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.storage.FileVersion
import sigil.tool.model.{ReadFileInput, ReadFileOutput}
import sigil.tool.{DiscoverySpec, Effect, Freshness, OutputBounds, PlaceholderInputDetector, Resolution, Tool, ToolExample, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Read a file from the [[FileSystemContext]]. Optional offset/limit
 * truncate the read window — useful for large files or paged
 * reading. Emits a typed [[ReadFileOutput]] (`content`,
 * `totalLines`, `linesRead`, optional `hash` for unwindowed reads).
 *
 * Apps that want sandboxing pass a `LocalFileSystemContext(basePath)`;
 * remote execution wraps this tool in [[sigil.tool.proxy.ProxyTool]].
 */
final class ReadFileTool(context: FileSystemContext) extends Tool {
  type Input  = ReadFileInput
  type Output = ReadFileOutput
  val io: ToolIO[ReadFileInput, ReadFileOutput] = ToolIO.derived[ReadFileInput, ReadFileOutput].withExamples(
    ToolExample("Read entire file", ReadFileInput(path = "README.md")),
    ToolExample("Read first 100 lines", ReadFileInput(path = "data.log", limit = Some(100))),
    ToolExample("Read lines 200-300", ReadFileInput(path = "data.log", offset = Some(200), limit = Some(100)))
  )
  override val name = ToolName("read_file")
  override val description =
    """Read the contents of a file. Use `offset` (0-indexed line) and `limit` to read a window of large files.
      |Returns `{content, totalLines, linesRead, hash?}`. `hash` is populated only on unwindowed reads.""".stripMargin

  // SelfBounded: a read whose content exceeds the inline cap is truncated
  // inline with offset/limit continuation guidance, never spilled to another
  // overflow file. This is what makes reading an externalized result safe —
  // the read-back can't cascade into yet another `.sigil/output` file
  // (sigil #370).
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable), output = OutputBounds.SelfBounded),
    discovery = DiscoverySpec(
      keywords = Set(
        "file", "read", "open", "cat", "view",
        "contents", "source", "examine", "inspect", "load", "show",
        "code", "text", "lines", "display", "fetch", "look"
      ),
      preferIfNoBetter = true
    )
  )


  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: ReadFileInput, ctx: ToolContext): Task[ToolResult[ReadFileOutput]] =
    PlaceholderInputDetector.validateNoPlaceholders("path" -> input.path) match {
      case Some(reason) => Task.pure(ToolResult.failure(message = reason))
      case None        =>
        WorkspacePathResolver.resolve(ctx, input.path)
          .flatMap(operate(input, _))
          // #394 — the inline cap is for AGENT context management. A workflow
          // step (`overflowLargeResults = false`) captures the result into a
          // variable a later step consumes verbatim, with no agent to paginate,
          // so it must receive the COMPLETE file — capping there corrupts the
          // data flow (the downstream step only ever sees the first window).
          .map(out => if (ctx.overflowLargeResults) capInline(out, input, ctx.sigil.inlineContentThreshold) else out)
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
