package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.PasswordUpdated
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

import java.util.concurrent.atomic.AtomicReference

final case class UpdatePasswordInput(@description("New password for the user") password: String) extends ToolInput derives RW

/**
 * `update_password` — replace the user's password.
 */
final class UpdatePasswordTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = UpdatePasswordInput
  type Output = TextToolOutput

  val inputRW: RW[UpdatePasswordInput] = summon[RW[UpdatePasswordInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("update_password")
  val description: String = "Update the user password."

  override def executeResult(input: UpdatePasswordInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    state.updateAndGet(env => env.copy(userAccount = env.userAccount.copy(password = input.password)))
    context.emit(PasswordUpdated(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput("Password updated.")))
  }
}
