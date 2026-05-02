package sigil.tool.fs

import fabric.{bool, obj}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.model.DeleteFileInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Delete a file. Result reports whether the file existed prior to
 * deletion (`deleted = true` for actually removed, `false` if the
 * path did not exist).
 */
final class DeleteFileTool(context: FileSystemContext)
  extends TypedTool[DeleteFileInput](
    name = ToolName("delete_file"),
    description = "Delete a file. Returns `deleted: true` if the file existed and was removed; `false` if it did not exist.",
    examples = List(
      ToolExample("Remove a temp file", DeleteFileInput(filePath = "/tmp/scratch.txt"))
    ),
    keywords = Set("file", "delete", "remove", "rm", "unlink")
  ) {
  override protected def executeTyped(input: DeleteFileInput, ctx: TurnContext): Stream[Event] =
    Stream.force(WorkspacePathResolver.resolve(ctx, input.filePath).flatMap { resolved =>
      context.deleteFile(resolved).map { existed =>
        Stream.emit[Event](FsToolEmit(obj("deleted" -> bool(existed)), ctx))
      }
    })
}
