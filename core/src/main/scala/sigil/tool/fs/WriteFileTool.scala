package sigil.tool.fs

import fabric.{bool, num, obj}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.model.WriteFileInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Write `content` (UTF-8) to `filePath`, creating parent directories
 * as needed. Result event carries `success` + `bytesWritten`.
 */
final class WriteFileTool(context: FileSystemContext)
  extends TypedTool[WriteFileInput](
    name = ToolName("write_file"),
    description = "Write content (UTF-8) to a file. Creates parent directories. Overwrites existing content.",
    examples = List(
      ToolExample("Save text to a new file", WriteFileInput(filePath = "notes.txt", content = "Some notes."))
    ),
    keywords = Set("file", "write", "save", "create", "output")
  ) {
  override protected def executeTyped(input: WriteFileInput, ctx: TurnContext): Stream[Event] =
    Stream.force(context.writeFile(input.filePath, input.content).map { bytes =>
      Stream.emit[Event](FsToolEmit(obj("success" -> bool(true), "bytesWritten" -> num(bytes)), ctx))
    })
}
