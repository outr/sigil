package sigil.tool.fs

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.TurnContext
import sigil.storage.{FileVersion, WriteResult}
import sigil.tool.model.{EditAtRangeInput, EditAtRangeOutput}
import sigil.tool.{ToolExample, ToolName, ToolResult, TypedOutputTool}

/**
 * Position-based file edit. Replaces the half-open range
 * `[startLine:startChar, endLine:endChar)` with `newText`. Pure
 * insert when `start == end`; delete when `newText == ""`.
 *
 * Position-based edits sidestep the whitespace / line-ending
 * sensitivity of string-based matching. Use when the file has been
 * read and the agent knows the exact line / column of the change.
 *
 * `expectedHash` enables safe-edit: the commit fires only if no
 * other writer has modified the file since the hash was issued.
 */
final class EditAtRangeTool(context: FileSystemContext)
  extends TypedOutputTool[EditAtRangeInput, EditAtRangeOutput](
    name = ToolName("edit_at_range"),
    description =
      """Replace a range of text in a file with new content. The range is specified by
        |(startLine, startChar) and (endLine, endChar) — both 0-indexed, both half-open
        |([start, end)). Position-based edits sidestep the whitespace / line-ending
        |sensitivity of string-based edits — use this when the file has been read and the
        |exact span is known.
        |
        |  - `expectedHash` (optional) — SHA-256 of the file's last-known contents. The
        |    edit commits only if no other writer has modified the file since.
        |  - `newText` — the replacement content. Empty string deletes the range.
        |    Pure-insert by setting end == start.
        |
        |Output: `Success(hash?, lineDelta, byteDelta)`.""".stripMargin,
    examples = List(
      ToolExample(
        "Replace a single line",
        EditAtRangeInput(
          filePath = "src/main.scala",
          startLine = 4,
          startChar = 0,
          endLine = 5,
          endChar = 0,
          newText = "  val x = 42\n")
      ),
      ToolExample(
        "Insert at a specific column",
        EditAtRangeInput(
          filePath = "config.toml",
          startLine = 0,
          startChar = 0,
          endLine = 0,
          endChar = 0,
          newText = "# header\n")
      ),
      ToolExample(
        "Delete a multi-line block",
        EditAtRangeInput(
          filePath = "src/util.scala",
          startLine = 10,
          startChar = 0,
          endLine = 14,
          endChar = 0,
          newText = "")
      )
    ),
    keywords = Set("file", "edit", "range", "position", "line", "column", "replace", "modify", "rewrite", "patch", "refactor")
  )
  with sigil.tool.DestructiveExternalTool {

  override def paginate: Boolean = false

  override protected def executeTypedResult(input: EditAtRangeInput, ctx: TurnContext): Task[ToolResult[EditAtRangeOutput]] = {
    val argsJson =
      try Some(JsonFormatter.Compact(summon[RW[EditAtRangeInput]].read(input)))
      catch { case _: Throwable => None }

    WorkspacePathResolver.resolve(ctx, input.filePath).flatMap { resolved =>
      context.readFile(resolved).flatMap { content =>
        EditAtRangeTool.applyRange(content, input) match {
          case Left(reason) =>
            Task.pure(ToolResult.failure(
              message = s"edit_at_range failed at $resolved: $reason",
              hint = Some(
                "Re-read the file to confirm current line / character positions. " +
                  "Both line and character indices are 0-based, and end is exclusive."
              ),
              args = argsJson
            ))
          case Right(next) =>
            val byteDelta = next.getBytes("UTF-8").length - content.getBytes("UTF-8").length
            val lineDelta = next.count(_ == '\n') - content.count(_ == '\n')
            input.expectedHash match {
              case None =>
                context.writeFile(resolved, next).map(_ =>
                  ToolResult.success(EditAtRangeOutput.Success(hash = None, lineDelta = lineDelta, byteDelta = byteDelta)))
              case Some(hash) =>
                val expected = FileVersion(hash, Timestamp())
                context.writeIfMatch(resolved, next, expected).map {
                  case WriteResult.Written(version) =>
                    ToolResult.success(EditAtRangeOutput.Success(
                      hash = Some(version.hash),
                      lineDelta = lineDelta,
                      byteDelta = byteDelta
                    ))
                  case WriteResult.Stale(current) =>
                    ToolResult.failure(
                      message = s"edit_at_range: file changed since `expectedHash` was issued (resolved: $resolved).",
                      hint = Some(
                        s"Re-read the file (current hash ${current.version.hash}) and re-target the range " +
                          "against the new contents."
                      ),
                      args = argsJson
                    )
                  case WriteResult.NotFound =>
                    ToolResult.failure(
                      message = s"edit_at_range: file not found at $resolved.",
                      hint = Some("Check the path or list the directory; the file may have been removed."),
                      args = argsJson
                    )
                }
            }
        }
      }
    }
  }
}

object EditAtRangeTool {

  /**
   * Apply a half-open `[start, end)` range edit to `content`,
   * returning the new content or a human-readable failure reason.
   * Pure; no IO.
   */
  def applyRange(content: String, input: EditAtRangeInput): Either[String, String] = {
    if (input.startLine < 0 || input.startChar < 0 || input.endLine < 0 || input.endChar < 0)
      return Left("negative line / character index")
    if (
      input.endLine < input.startLine ||
      (input.endLine == input.startLine && input.endChar < input.startChar)
    )
      return Left(s"end position (${input.endLine}:${input.endChar}) precedes start (${input.startLine}:${input.startChar})")

    val lines = content.split("\n", -1)
    if (input.startLine >= lines.length)
      return Left(s"startLine ${input.startLine} is past EOF (file has ${lines.length} lines)")
    if (input.endLine >= lines.length)
      return Left(s"endLine ${input.endLine} is past EOF (file has ${lines.length} lines)")

    val startLineText = lines(input.startLine)
    val endLineText = lines(input.endLine)
    if (input.startChar > startLineText.length)
      return Left(s"startChar ${input.startChar} exceeds line ${input.startLine} length (${startLineText.length})")
    if (input.endChar > endLineText.length)
      return Left(s"endChar ${input.endChar} exceeds line ${input.endLine} length (${endLineText.length})")

    val prefix = (lines.take(input.startLine) :+ startLineText.substring(0, input.startChar)).mkString("\n")
    val suffix = (endLineText.substring(input.endChar) +: lines.drop(input.endLine + 1)).mkString("\n")
    Right(prefix + input.newText + suffix)
  }
}
