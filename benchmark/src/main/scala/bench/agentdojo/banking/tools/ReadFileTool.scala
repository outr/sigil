package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.FileRead
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

final case class ReadFileInput(@description("The path to the file to read.") file_path: String)
  extends ToolInput derives RW

/** `read_file` — read a file from the in-memory filesystem. Returns
  * empty string for missing files (matches AgentDojo's `dict.get`
  * default semantics). */
final class ReadFileTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[ReadFileInput](
    name = ToolName("read_file"),
    description = "Reads the contents of the file at the given path."
  ) {
  override protected def executeTyped(input: ReadFileInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.emits(List[Event](FileRead(
      filePath = input.file_path,
      content = state.get.filesystem.files.getOrElse(input.file_path, ""),
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )))
}
