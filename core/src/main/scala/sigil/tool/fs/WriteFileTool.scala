package sigil.tool.fs

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.tool.ToolContext
import sigil.storage.{FileVersion, WriteResult}
import sigil.tool.model.{WriteFileInput, WriteFileOutput}
import sigil.tool.{PlaceholderInputDetector, Tool, ToolExample, ToolName, ToolResult}

/**
 * Write `content` (UTF-8) to `path`, creating parent directories
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
  extends Tool with sigil.tool.DestructiveExternalTool {
  type Input  = WriteFileInput
  type Output = WriteFileOutput
  val inputRW  = summon[RW[WriteFileInput]]
  val outputRW = summon[RW[WriteFileOutput]]
  val name = ToolName("write_file")
  val description =
    """Write content (UTF-8) to a file. Creates parent directories. Overwrites existing content.
      |
      |Pass `expectedHash` (SHA-256 of the file's last-known contents) to enable safe-edit:
      |the write commits only if no other writer has modified the file since you saw it. On
      |mismatch, the result carries the file's current contents so you can re-evaluate the
      |change against the new state.
      |
      |Output: `Success(bytesWritten, hash?) | Stale(currentHash, currentContent) | NotFound`.""".stripMargin
  override val examples = List(
    ToolExample("Save text to a new file", WriteFileInput(path = "notes.txt", content = "Some notes.")),
    ToolExample(
      "Update a file safely",
      WriteFileInput(path = "config.yaml", content = "debug: true", expectedHash = Some("abc123..."))
    )
  )
  override val keywords = Set("file", "write", "save", "create", "output")

  /** Non-Success WriteFileOutputs (Stale, NotFound) are logical failures of
    * the WRITE operation — the commit did NOT land. Surfacing them through
    * `executeResult` lets the agent's frame projection render them as
    * Tool-role Failure Messages with actionable hints, instead of a
    * Success-shaped `ToolResults` the agent might gloss over and incorrectly
    * report as "I saved the file." */
  override def executeResult(input: WriteFileInput, ctx: ToolContext): Task[ToolResult[WriteFileOutput]] =
    PlaceholderInputDetector.validateNoPlaceholders("path" -> input.path) match {
      case Some(reason) => Task.pure(ToolResult.failure(message = reason))
      case None        => runWrite(input, ctx)
    }

  private def renderInputArgs(input: WriteFileInput): Option[String] =
    try Some(JsonFormatter.Compact(inputRW.read(input)))
    catch { case _: Throwable => None }

  private def runWrite(input: WriteFileInput, ctx: ToolContext): Task[ToolResult[WriteFileOutput]] =
    WorkspacePathResolver.resolve(ctx, input.path).flatMap { resolved =>
      val argsJson = renderInputArgs(input)
      input.expectedHash match {
        case None =>
          context.writeFile(resolved, input.content).map { bytes =>
            ToolResult.success(WriteFileOutput.Success(bytesWritten = bytes, hash = None))
          }
        case Some(hash) =>
          val expected = FileVersion(hash, Timestamp())
          context.writeIfMatch(resolved, input.content, expected).map {
            case WriteResult.Written(version) =>
              ToolResult.success(WriteFileOutput.Success(bytesWritten = input.content.getBytes("UTF-8").length.toLong,
                                                         hash         = Some(version.hash)))
            case WriteResult.Stale(current) =>
              ToolResult.failure(
                message = s"write_file: file changed since `expectedHash` was issued (resolved: $resolved).",
                hint = Some(
                  s"Re-read the file (current hash ${current.version.hash}) and decide whether the " +
                    "intended write still applies, then retry with the fresh hash."
                ),
                args = argsJson
              )
            case WriteResult.NotFound =>
              ToolResult.failure(
                message = s"write_file: file not found at $resolved.",
                hint = Some("Check the path or list the directory; the file may have been removed."),
                args = argsJson
              )
          }
      }
    }
}
