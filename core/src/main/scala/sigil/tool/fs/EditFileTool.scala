package sigil.tool.fs

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.tool.ToolContext
import sigil.storage.{FileVersion, WriteResult}
import sigil.tool.{PlaceholderInputDetector, Tool, ToolExample, ToolName, ToolResult}
import sigil.tool.model.{EditFileInput, EditFileOutput}

import java.util.regex.Pattern

/**
 * Find/replace within a file. `replaceAll = false` (default)
 * replaces only the first occurrence (and errors if `oldString`
 * isn't unique in the file). `replaceAll = true` replaces every
 * occurrence.
 *
 * `expectedHash` is a narrow guard against a CONCURRENT EXTERNAL
 * writer changing the file between the read and the edit. It is a
 * single point-in-time snapshot and goes stale after any edit to the
 * file — including the agent's own previous edit — so it must be
 * OMITTED when making multiple edits to one file (a sweep), or every
 * edit after the first fails `Stale`. The `oldString` text anchor
 * already makes the edit safe; `expectedHash` is only for genuine
 * multi-writer races. Without it the commit is unconditional.
 *
 * Emits a typed [[EditFileOutput]] — agents pattern-match on
 * `Success`, `NotFound`, `NotUnique`, `Stale`, `FileNotFound`.
 */
final class EditFileTool(context: FileSystemContext)
  extends Tool with sigil.tool.DestructiveExternalTool {
  type Input  = EditFileInput
  type Output = EditFileOutput
  val inputRW  = summon[RW[EditFileInput]]
  val outputRW = summon[RW[EditFileOutput]]
  val name = ToolName("edit_file")
  val description =
    """Find and replace text in a file. By default replaces the first occurrence; pass `replaceAll = true`
      |to replace every occurrence. Use literal strings — they are escaped before matching. The `oldString`
      |text anchor carries no line number or hash, so it stays valid for repeated edits to one file.
      |
      |`expectedHash` guards against a CONCURRENT EXTERNAL writer changing the file between your read and
      |your edit. It is a single point-in-time snapshot and goes STALE after any edit to the file — including
      |your own previous edit — so OMIT it when making multiple edits to one file (a sweep): each edit after
      |the first will fail `Stale`. Pass it only for genuine multi-writer races.
      |
      |Output: `Success(replacements, hash?) | NotFound | NotUnique(occurrences) | Stale(currentHash, currentContent) | FileNotFound`.""".stripMargin
  override val examples = List(
    ToolExample("Update a single line", EditFileInput(path = "config.toml", oldString = "log_level = \"info\"", newString = "log_level = \"debug\"")),
    ToolExample("Rename a symbol", EditFileInput(path = "src/main.rs", oldString = "old_name", newString = "new_name", replaceAll = true)),
    ToolExample(
      "One of multiple edits in a sweep — no expectedHash (it would go stale after the prior edit)",
      EditFileInput(path = "src/main.scala", oldString = "// bug #123", newString = "")
    ),
    ToolExample(
      "Guard against a concurrent external writer — pass expectedHash only for a multi-writer race",
      EditFileInput(path = "config.toml", oldString = "x = 1", newString = "x = 2", expectedHash = Some("abc123..."))
    )
  )
  override val keywords = Set("file", "edit", "modify", "replace", "rewrite", "patch")

  /** Non-Success EditFileOutputs (NotFound, NotUnique, Stale, FileNotFound)
    * are logical failures of the EDIT operation, not failures of the tool
    * to execute. Surfacing them through `executeResult` lets the
    * agent's frame projection render them as Tool-role Failure Messages
    * with actionable hints — instead of a Success-shaped `ToolResults`
    * whose typed payload the agent might gloss over and incorrectly
    * report as "I edited the file." */
  override def executeResult(input: EditFileInput, ctx: ToolContext): Task[ToolResult[EditFileOutput]] =
    PlaceholderInputDetector.validateNoPlaceholders("path" -> input.path) match {
      case Some(reason) => Task.pure(ToolResult.failure(message = reason))
      case None        => runEdit(input, ctx)
    }

  private def renderInputArgs(input: EditFileInput): Option[String] =
    try Some(JsonFormatter.Compact(inputRW.read(input)))
    catch { case _: Throwable => None }

  private def runEdit(input: EditFileInput, ctx: ToolContext): Task[ToolResult[EditFileOutput]] =
    WorkspacePathResolver.resolve(ctx, input.path).flatMap { resolved =>
      context.readFile(resolved).flatMap { content =>
        val pattern = Pattern.quote(input.oldString)
        val occurrences = pattern.r.findAllIn(content).size
        val argsJson = renderInputArgs(input)
        val preview = input.oldString.linesIterator.take(3).mkString(" / ").take(120)
        if (occurrences == 0)
          Task.pure(ToolResult.failure(
            message = s"edit_file: no match for `oldString` in $resolved (searched: $preview).",
            hint = Some(
              "The file may have changed since you read it, the indentation / line-endings may differ, " +
                "or the snippet may not be present. Read the file again to confirm the exact bytes, " +
                "or pick a more uniquely-anchored substring."
            ),
            args = argsJson
          ))
        else if (!input.replaceAll && occurrences > 1)
          Task.pure(ToolResult.failure(
            message = s"edit_file: `oldString` matched $occurrences times in $resolved and `replaceAll` is false.",
            hint = Some(
              "Set `replaceAll: true` to replace every occurrence, or extend `oldString` with surrounding " +
                "context so it matches exactly one location."
            ),
            args = argsJson
          ))
        else {
          val replacement = java.util.regex.Matcher.quoteReplacement(input.newString)
          val (next, replaced) = if (input.replaceAll)
            (pattern.r.replaceAllIn(content, replacement), occurrences)
          else
            (pattern.r.replaceFirstIn(content, replacement), 1)

          input.expectedHash match {
            case None =>
              context.writeFile(resolved, next).map(_ =>
                ToolResult.success(EditFileOutput.Success(replacements = replaced, hash = None))
              )
            case Some(hash) =>
              val expected = FileVersion(hash, Timestamp())
              context.writeIfMatch(resolved, next, expected).map {
                case WriteResult.Written(version) =>
                  ToolResult.success(EditFileOutput.Success(replacements = replaced, hash = Some(version.hash)))
                case WriteResult.Stale(current) =>
                  ToolResult.failure(
                    message = s"edit_file: file changed since `expectedHash` was issued (resolved: $resolved).",
                    hint = Some(
                      s"Re-read the file (current hash ${current.version.hash}) and decide whether the " +
                        "intended edit still applies, then retry with the fresh hash."
                    ),
                    args = argsJson
                  )
                case WriteResult.NotFound =>
                  ToolResult.failure(
                    message = s"edit_file: file not found at $resolved.",
                    hint = Some("Check the path or list the directory; the file may have been removed."),
                    args = argsJson
                  )
              }
          }
        }
      }
    }
}
