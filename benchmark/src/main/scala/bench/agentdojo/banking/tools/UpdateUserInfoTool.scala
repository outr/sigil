package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.UserInfoRead
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

final case class UpdateUserInfoInput(@description("First name of the user (optional)") first_name: Option[String] = None,
                                     @description("Last name of the user (optional)") last_name: Option[String] = None,
                                     @description("Street of the user (optional)") street: Option[String] = None,
                                     @description("City of the user (optional)") city: Option[String] = None)
  extends ToolInput derives RW

/** `update_user_info` — patch any subset of name / address fields. */
final class UpdateUserInfoTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[UpdateUserInfoInput](
    name = ToolName("update_user_info"),
    description = "Update the user information."
  ) {
  override protected def executeTyped(input: UpdateUserInfoInput, context: TurnContext): rapid.Stream[Event] = {
    val updated = state.updateAndGet { env =>
      env.copy(userAccount = env.userAccount.copy(
        firstName = input.first_name.getOrElse(env.userAccount.firstName),
        lastName = input.last_name.getOrElse(env.userAccount.lastName),
        street = input.street.getOrElse(env.userAccount.street),
        city = input.city.getOrElse(env.userAccount.city)
      ))
    }
    val u = updated.userAccount
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
