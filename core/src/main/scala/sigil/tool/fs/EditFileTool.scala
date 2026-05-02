package sigil.tool.fs

import fabric.{bool, num, obj, str}
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.storage.{FileVersion, WriteResult}
import sigil.tool.model.EditFileInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

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
 */
final class EditFileTool(context: FileSystemContext)
  extends TypedTool[EditFileInput](
    name = ToolName("edit_file"),
    description =
      """Find and replace text in a file. By default replaces the first occurrence; pass `replaceAll = true`
        |to replace every occurrence. Use literal strings — they are escaped before matching.
        |
        |Pass `expectedHash` (SHA-256 of the file when you last read it) to enable safe-edit: the change
        |commits only if no other writer has modified the file since. On mismatch, the tool returns the
        |file's current contents so you can re-evaluate the edit against the new state.""".stripMargin,
    examples = List(
      ToolExample("Update a single line", EditFileInput(filePath = "config.toml", oldString = "log_level = \"info\"", newString = "log_level = \"debug\"")),
      ToolExample("Rename a symbol", EditFileInput(filePath = "src/main.rs", oldString = "old_name", newString = "new_name", replaceAll = true)),
      ToolExample(
        "Edit safely against a known hash",
        EditFileInput(filePath = "config.toml", oldString = "x = 1", newString = "x = 2", expectedHash = Some("abc123..."))
      )
    ),
    keywords = Set("file", "edit", "modify", "replace", "rewrite", "patch")
  ) {
  override protected def executeTyped(input: EditFileInput, ctx: TurnContext): Stream[Event] = Stream.force(
    WorkspacePathResolver.resolve(ctx, input.filePath).flatMap { resolved =>
      context.readFile(resolved).flatMap { content =>
        val pattern = Pattern.quote(input.oldString)
        val occurrences = pattern.r.findAllIn(content).size
        if (occurrences == 0) {
          Task.pure(Stream.emit[Event](FsToolEmit(
            obj("success" -> bool(false), "error" -> str("oldString not found")),
            ctx
          )))
        } else if (!input.replaceAll && occurrences > 1) {
          Task.pure(Stream.emit[Event](FsToolEmit(
            obj("success" -> bool(false), "error" -> str("oldString not unique; pass replaceAll = true to replace all occurrences")),
            ctx
          )))
        } else {
          val replacement = java.util.regex.Matcher.quoteReplacement(input.newString)
          val (next, replaced) = if (input.replaceAll)
            (pattern.r.replaceAllIn(content, replacement), occurrences)
          else
            (pattern.r.replaceFirstIn(content, replacement), 1)

          input.expectedHash match {
            case None =>
              context.writeFile(resolved, next).map { _ =>
                Stream.emit[Event](FsToolEmit(obj("success" -> bool(true), "replacements" -> num(replaced)), ctx))
              }
            case Some(hash) =>
              val expected = FileVersion(hash, Timestamp())
              context.writeIfMatch(resolved, next, expected).map { result =>
                val payload = result match {
                  case WriteResult.Written(version) =>
                    obj(
                      "result" -> str("written"),
                      "hash" -> str(version.hash),
                      "replacements" -> num(replaced)
                    )
                  case other => WriteResultRender(other, next)
                }
                Stream.emit[Event](FsToolEmit(payload, ctx))
              }
          }
        }
      }
    }
  )
}
