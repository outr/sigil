package sigil.tool.fs

import rapid.Task
import sigil.TurnContext
import sigil.tool.model.{DeleteFileInput, DeleteFileOutput}
import sigil.tool.{PlaceholderInputDetector, ToolExample, ToolName, ToolResult, TypedOutputTool}

/**
 * Delete a file. Emits a typed [[DeleteFileOutput]] reporting
 * whether the file existed prior to deletion (`deleted = true` for
 * actually removed, `false` if the path did not exist).
 */
final class DeleteFileTool(context: FileSystemContext)
  extends TypedOutputTool[DeleteFileInput, DeleteFileOutput](
    name = ToolName("delete_file"),
    description = "Delete a file. Returns `{deleted: Boolean}` — true when the file existed and was removed; false when it did not exist.",
    examples = List(
      ToolExample("Remove a temp file", DeleteFileInput(filePath = "/tmp/scratch.txt"))
    ),
    keywords = Set("file", "delete", "remove", "rm", "unlink")
  ) with sigil.tool.DestructiveExternalTool {
  override def paginate: Boolean = false

  override protected def executeTypedResult(input: DeleteFileInput, ctx: TurnContext): Task[ToolResult[DeleteFileOutput]] =
    PlaceholderInputDetector.validateNoPlaceholders("filePath" -> input.filePath) match {
      case Some(reason) => Task.pure(ToolResult.failure(message = reason))
      case None =>
        WorkspacePathResolver.resolve(ctx, input.filePath).flatMap { resolved =>
          context.deleteFile(resolved).map(existed => ToolResult.success(DeleteFileOutput(deleted = existed)))
        }
    }
}
