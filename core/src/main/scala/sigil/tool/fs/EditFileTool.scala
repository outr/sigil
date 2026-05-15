package sigil.tool.fs

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.TurnContext
import sigil.storage.{FileVersion, WriteResult}
import sigil.tool.{ToolExample, ToolName, ToolResult, TypedOutputTool}
import sigil.tool.model.{EditFileInput, EditFileOutput}

import java.util.regex.Pattern

/**
 * Find/replace within a file. `replaceAll = false` (default)
 * replaces only the first occurrence (and errors if `oldString`
 * isn't unique in the file). `replaceAll = true` replaces every
 * occurrence.
 *
 * Pass `expectedHash` to enable safe-edit: the replacement applies
 * to the file's current contents, then commits only if no other
 * writer has modified the file since `expectedHash` was issued.
 * On mismatch the tool returns the file's freshest contents so the
 * agent can re-evaluate the edit. Without `expectedHash`, the
 * commit is unconditional (legacy single-agent behavior).
 *
 * Emits a typed [[EditFileOutput]] — agents pattern-match on
 * `Success`, `NotFound`, `NotUnique`, `Stale`, `FileNotFound`.
 */
final class EditFileTool(context: FileSystemContext)
  extends TypedOutputTool[EditFileInput, EditFileOutput](
    name = ToolName("edit_file"),
    description =
      """Find and replace text in a file. By default replaces the first occurrence; pass `replaceAll = true`
        |to replace every occurrence. Use literal strings — they are escaped before matching.
        |
        |Pass `expectedHash` (SHA-256 of the file when you last read it) to enable safe-edit: the change
        |commits only if no other writer has modified the file since. On mismatch, the tool returns the
        |file's current contents so you can re-evaluate the edit against the new state.
        |
        |Output: `Success(replacements, hash?) | NotFound | NotUnique(occurrences) | Stale(currentHash, currentContent) | FileNotFound`.""".stripMargin,
    examples = List(
      ToolExample("Update a single line", EditFileInput(filePath = "config.toml", oldString = "log_level = \"info\"", newString = "log_level = \"debug\"")),
      ToolExample("Rename a symbol", EditFileInput(filePath = "src/main.rs", oldString = "old_name", newString = "new_name", replaceAll = true)),
      ToolExample(
        "Edit safely against a known hash",
        EditFileInput(filePath = "config.toml", oldString = "x = 1", newString = "x = 2", expectedHash = Some("abc123..."))
      )
    ),
    keywords = Set("file", "edit", "modify", "replace", "rewrite", "patch")
  ) with sigil.tool.DestructiveExternalTool {

  /** Non-Success EditFileOutputs (NotFound, NotUnique, Stale, FileNotFound)
    * are logical failures of the EDIT operation, not failures of the tool
    * to execute. Surfacing them through `executeTypedResult` lets the
    * agent's frame projection render them as Tool-role Failure Messages
    * with actionable hints — instead of a Success-shaped `ToolResults`
    * whose typed payload the agent might gloss over and incorrectly
    * report as "I edited the file." */
  override protected def executeTypedResult(input: EditFileInput, ctx: TurnContext): Task[ToolResult[EditFileOutput]] =
    WorkspacePathResolver.resolve(ctx, input.filePath).flatMap { resolved =>
      context.readFile(resolved).flatMap { content =>
        val pattern = Pattern.quote(input.oldString)
        val occurrences = pattern.r.findAllIn(content).size
        val argsJson =
          try Some(JsonFormatter.Compact(summon[RW[EditFileInput]].read(input)))
          catch { case _: Throwable => None }
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
