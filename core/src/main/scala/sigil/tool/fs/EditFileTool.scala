package sigil.tool.fs

import fabric.{bool, num, obj, str}
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.model.EditFileInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

import java.util.regex.Pattern

/**
 * Find/replace within a file. `replaceAll = false` (default)
 * replaces only the first occurrence (and errors if `oldString`
 * isn't unique in the file). `replaceAll = true` replaces every
 * occurrence. Result reports `replacements` count.
 */
final class EditFileTool(context: FileSystemContext)
  extends TypedTool[EditFileInput](
    name = ToolName("edit_file"),
    description =
      """Find and replace text in a file. By default replaces the first occurrence; pass `replaceAll = true`
        |to replace every occurrence. Use literal strings — they are escaped before matching.""".stripMargin,
    examples = List(
      ToolExample("Update a single line", EditFileInput(filePath = "config.toml", oldString = "log_level = \"info\"", newString = "log_level = \"debug\"")),
      ToolExample("Rename a symbol", EditFileInput(filePath = "src/main.rs", oldString = "old_name", newString = "new_name", replaceAll = true))
    ),
    keywords = Set("file", "edit", "modify", "replace", "rewrite", "patch")
  ) {
  override protected def executeTyped(input: EditFileInput, ctx: TurnContext): Stream[Event] = Stream.force(
    context.readFile(input.filePath).flatMap { content =>
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
        context.writeFile(input.filePath, next).map { _ =>
          Stream.emit[Event](FsToolEmit(obj("success" -> bool(true), "replacements" -> num(replaced)), ctx))
        }
      }
    }
  )
}
