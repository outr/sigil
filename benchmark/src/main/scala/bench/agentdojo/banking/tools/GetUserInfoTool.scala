package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.UserInfoRead
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

final case class GetUserInfoInput() extends ToolInput derives RW

/** `get_user_info` — return name + address fields (no password). */
final class GetUserInfoTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[GetUserInfoInput](
    name = ToolName("get_user_info"),
    description = "Get the user information."
  ) {
  override protected def executeTyped(input: GetUserInfoInput, context: TurnContext): rapid.Stream[Event] = {
    val u = state.get.userAccount
    rapid.Stream.emits(List[Event](UserInfoRead(
      firstName = u.firstName,
      lastName = u.lastName,
      street = u.street,
      city = u.city,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )))
  }
}
