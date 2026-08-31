package sigil.tool.fs

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.{DeleteFileInput, DeleteFileOutput}
import sigil.tool.{
  DiscoverySpec, Effect, MutationTarget, MutationTargeting, PlaceholderInputDetector, Resolution, Tool, ToolExample, ToolIO, ToolName,
  ToolProfile, ToolResult, ToolSpec
}

/**
 * Delete a file. Emits a typed [[DeleteFileOutput]] reporting
 * whether the file existed prior to deletion (`deleted = true` for
 * actually removed, `false` if the path did not exist).
 */
final class DeleteFileTool(context: FileSystemContext) extends Tool {
  type Input = DeleteFileInput
  type Output = DeleteFileOutput
  val io: ToolIO[DeleteFileInput, DeleteFileOutput] = ToolIO.derived[DeleteFileInput, DeleteFileOutput].withExamples(
    ToolExample("Remove a temp file", DeleteFileInput(path = "/tmp/scratch.txt"))
  )
  override val name = ToolName("delete_file")
  override val description =
    "Delete a file. Returns `{deleted: Boolean}` — true when the file existed and was removed; false when it did not exist."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(
      effect = Effect.Destructive(
        target = MutationTargeting.typed[DeleteFileInput](i => Some(MutationTarget(i.path))),
        consequence = "DESTRUCTIVE."
      )
    ),
    discovery = DiscoverySpec(keywords = Set("file", "delete", "remove", "rm", "unlink"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DeleteFileInput, ctx: ToolContext): Task[ToolResult[DeleteFileOutput]] =
    PlaceholderInputDetector.validateNoPlaceholders("path" -> input.path) match {
      case Some(reason) => Task.pure(ToolResult.failure(message = reason))
      case None =>
        WorkspacePathResolver.resolve(ctx, input.path).flatMap { resolved =>
          context.deleteFile(resolved).map(existed => ToolResult.success(DeleteFileOutput(deleted = existed)))
        }
    }
}
