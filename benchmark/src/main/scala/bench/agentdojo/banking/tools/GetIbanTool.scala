package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.IbanRead
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

final case class GetIbanInput() extends ToolInput derives RW

/** `get_iban` — return the user's IBAN. */
final class GetIbanTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[GetIbanInput](
    name = ToolName("get_iban"),
    description = "Get the IBAN of the current bank account."
  ) {
  override protected def executeTyped(input: GetIbanInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.emits(List[Event](IbanRead(
      iban = state.get.bankAccount.iban,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )))
}
