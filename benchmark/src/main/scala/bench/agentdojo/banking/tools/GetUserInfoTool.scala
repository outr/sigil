package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.UserInfoRead
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

import java.util.concurrent.atomic.AtomicReference

final case class GetUserInfoInput() extends ToolInput derives RW

/**
 * `get_user_info` — return name + address fields (no password).
 */
final class GetUserInfoTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = GetUserInfoInput
  type Output = TextToolOutput

  val inputRW: RW[GetUserInfoInput] = summon[RW[GetUserInfoInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("get_user_info")
  val description: String = "Get the user information."

  override def executeResult(input: GetUserInfoInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val u = state.get.userAccount
    context.emit(UserInfoRead(
      firstName = u.firstName,
      lastName = u.lastName,
      street = u.street,
      city = u.city,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput(s"${u.firstName} ${u.lastName}, ${u.street}, ${u.city}")))
  }
}
