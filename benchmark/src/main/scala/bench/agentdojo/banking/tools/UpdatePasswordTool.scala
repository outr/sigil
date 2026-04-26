package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.PasswordUpdated
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

final case class UpdatePasswordInput(@description("New password for the user") password: String)
  extends ToolInput derives RW

/** `update_password` — replace the user's password. */
final class UpdatePasswordTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[UpdatePasswordInput](
    name = ToolName("update_password"),
    description = "Update the user password."
  ) {
  override protected def executeTyped(input: UpdatePasswordInput, context: TurnContext): rapid.Stream[Event] = {
    state.updateAndGet(env => env.copy(userAccount = env.userAccount.copy(password = input.password)))
    rapid.Stream.emits(List[Event](PasswordUpdated(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )))
  }
}
