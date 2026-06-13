package sigil.tool.fs

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.{DeleteFileInput, DeleteFileOutput}
import sigil.tool.{PlaceholderInputDetector, Tool, ToolExample, ToolName, ToolResult}

/**
 * Delete a file. Emits a typed [[DeleteFileOutput]] reporting
 * whether the file existed prior to deletion (`deleted = true` for
 * actually removed, `false` if the path did not exist).
 */
final class DeleteFileTool(context: FileSystemContext)
  extends Tool with sigil.tool.DestructiveExternalTool {
  type Input  = DeleteFileInput
  type Output = DeleteFileOutput
  val inputRW  = summon[RW[DeleteFileInput]]
  val outputRW = summon[RW[DeleteFileOutput]]
  val name        = ToolName("delete_file")
  val description = "Delete a file. Returns `{deleted: Boolean}` — true when the file existed and was removed; false when it did not exist."
  override val examples = List(
    ToolExample("Remove a temp file", DeleteFileInput(path = "/tmp/scratch.txt"))
  )
  override val keywords = Set("file", "delete", "remove", "rm", "unlink")

  override def executeResult(input: DeleteFileInput, ctx: ToolContext): Task[ToolResult[DeleteFileOutput]] =
    PlaceholderInputDetector.validateNoPlaceholders("path" -> input.path) match {
      case Some(reason) => Task.pure(ToolResult.failure(message = reason))
      case None =>
        WorkspacePathResolver.resolve(ctx, input.path).flatMap { resolved =>
          context.deleteFile(resolved).map(existed => ToolResult.success(DeleteFileOutput(deleted = existed)))
        }
    }
}
