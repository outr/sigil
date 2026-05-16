package sigil.tool.fs

import lightdb.time.Timestamp
import rapid.Task
import sigil.TurnContext
import sigil.storage.{FileVersion, WriteResult}
import sigil.tool.model.{WriteFileInput, WriteFileOutput}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * Write `content` (UTF-8) to `filePath`, creating parent directories
 * as needed.
 *
 * When `expectedHash` is set, the write uses safe-edit semantics:
 * the file commits only if its current SHA-256 still matches
 * `expectedHash`, otherwise the tool returns a [[WriteFileOutput.Stale]]
 * carrying the file's current contents so the agent can retry against
 * the fresh state. Without `expectedHash` the write is unconditional
 * — the legacy single-agent path returns [[WriteFileOutput.Success]]
 * with `hash = None`.
 */
final class WriteFileTool(context: FileSystemContext)
  extends TypedOutputTool[WriteFileInput, WriteFileOutput](
    name = ToolName("write_file"),
    description =
      """Write content (UTF-8) to a file. Creates parent directories. Overwrites existing content.
        |
        |Pass `expectedHash` (SHA-256 of the file's last-known contents) to enable safe-edit:
        |the write commits only if no other writer has modified the file since you saw it. On
        |mismatch, the result carries the file's current contents so you can re-evaluate the
        |change against the new state.
        |
        |Output: `Success(bytesWritten, hash?) | Stale(currentHash, currentContent) | NotFound`.""".stripMargin,
    examples = List(
      ToolExample("Save text to a new file", WriteFileInput(filePath = "notes.txt", content = "Some notes.")),
      ToolExample(
        "Update a file safely",
        WriteFileInput(filePath = "config.yaml", content = "debug: true", expectedHash = Some("abc123..."))
      )
    ),
    keywords = Set("file", "write", "save", "create", "output")
  ) with sigil.tool.DestructiveExternalTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: WriteFileInput, ctx: TurnContext): Task[WriteFileOutput] =
    WorkspacePathResolver.resolve(ctx, input.filePath).flatMap { resolved =>
      input.expectedHash match {
        case None =>
          context.writeFile(resolved, input.content).map { bytes =>
            WriteFileOutput.Success(bytesWritten = bytes, hash = None)
          }
        case Some(hash) =>
          val expected = FileVersion(hash, Timestamp())
          context.writeIfMatch(resolved, input.content, expected).map {
            case WriteResult.Written(version) =>
              WriteFileOutput.Success(bytesWritten = input.content.getBytes("UTF-8").length.toLong,
                                       hash         = Some(version.hash))
            case WriteResult.Stale(current) =>
              WriteFileOutput.Stale(currentHash = current.version.hash, currentContent = current.asText)
            case WriteResult.NotFound =>
              WriteFileOutput.NotFound
          }
      }
    }
}
