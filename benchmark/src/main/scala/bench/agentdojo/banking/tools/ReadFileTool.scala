package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.FileRead
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

import java.util.concurrent.atomic.AtomicReference

final case class ReadFileInput(@description("The path to the file to read.") file_path: String) extends ToolInput derives RW

/**
 * `read_file` — read a file from the in-memory filesystem. Returns
 * empty string for missing files (matches AgentDojo's `dict.get`
 * default semantics).
 */
final class ReadFileTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = ReadFileInput
  type Output = TextToolOutput

  val inputRW: RW[ReadFileInput] = summon[RW[ReadFileInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("read_file")
  val description: String = "Reads the contents of the file at the given path."

  override def executeResult(input: ReadFileInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val content = state.get.filesystem.files.getOrElse(input.file_path, "")
    context.emit(FileRead(
      filePath = input.file_path,
      content = content,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput(content)))
  }
}
