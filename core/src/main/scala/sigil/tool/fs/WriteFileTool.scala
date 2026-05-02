package sigil.tool.fs

import fabric.{bool, num, obj, str}
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.storage.{FileVersion, WriteResult}
import sigil.tool.model.WriteFileInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Write `content` (UTF-8) to `filePath`, creating parent directories
 * as needed.
 *
 * When `expectedHash` is set, the write uses safe-edit semantics:
 * the file commits only if its current SHA-256 still matches
 * `expectedHash`, otherwise the tool returns a `stale` result with
 * the file's current contents so the agent can retry against the
 * fresh state. Without `expectedHash` the write is unconditional —
 * the legacy single-agent path.
 *
 * Result event payload shapes:
 *
 *   - unconditional success: `{ "success": true, "bytesWritten": N }`
 *   - safe-edit success: `{ "result": "written", "hash": "...", "bytesWritten": N }`
 *   - safe-edit stale: `{ "result": "stale", "currentHash": "...", "currentContent": "..." }`
 *   - safe-edit not-found: `{ "result": "not_found" }`
 */
final class WriteFileTool(context: FileSystemContext)
  extends TypedTool[WriteFileInput](
    name = ToolName("write_file"),
    description =
      """Write content (UTF-8) to a file. Creates parent directories. Overwrites existing content.
        |
        |Pass `expectedHash` (SHA-256 of the file's last-known contents, returned by `read_file` /
        |`edit_file` / `write_file`) to enable safe-edit: the write commits only if no other writer
        |has modified the file since you saw it. On mismatch, the tool returns the file's current
        |contents so you can re-evaluate the change against the new state.""".stripMargin,
    examples = List(
      ToolExample("Save text to a new file", WriteFileInput(filePath = "notes.txt", content = "Some notes.")),
      ToolExample(
        "Update a file safely",
        WriteFileInput(filePath = "config.yaml", content = "debug: true", expectedHash = Some("abc123..."))
      )
    ),
    keywords = Set("file", "write", "save", "create", "output")
  ) {
  override protected def executeTyped(input: WriteFileInput, ctx: TurnContext): Stream[Event] = Stream.force(
    WorkspacePathResolver.resolve(ctx, input.filePath).flatMap { resolved =>
      input.expectedHash match {
        case None =>
          context.writeFile(resolved, input.content).map { bytes =>
            Stream.emit[Event](FsToolEmit(obj("success" -> bool(true), "bytesWritten" -> num(bytes)), ctx))
          }
        case Some(hash) =>
          val expected = FileVersion(hash, Timestamp())
          context.writeIfMatch(resolved, input.content, expected).map { result =>
            Stream.emit[Event](FsToolEmit(WriteResultRender(result, input.content), ctx))
          }
      }
    }
  )
}
