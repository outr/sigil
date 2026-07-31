package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.UserInfoRead
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, TextToolOutput, Tool, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

import java.util.concurrent.atomic.AtomicReference

final case class GetUserInfoInput() extends ToolInput derives RW

/** `get_user_info` — return name + address fields (no password). */
final class GetUserInfoTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = GetUserInfoInput
  type Output = TextToolOutput

  val inputRW: RW[GetUserInfoInput] = summon[RW[GetUserInfoInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  override val name: ToolName = ToolName("get_user_info")
  override val description: String = "Get the user information."

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("bank", "user", "info", "account"))
  )

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
