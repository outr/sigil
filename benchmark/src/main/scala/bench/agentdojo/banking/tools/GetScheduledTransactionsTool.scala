package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.ScheduledTransactionsRead
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

final case class GetScheduledTransactionsInput() extends ToolInput derives RW

/** `get_scheduled_transactions` — return the scheduled-transaction list. */
final class GetScheduledTransactionsTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[GetScheduledTransactionsInput](
    name = ToolName("get_scheduled_transactions"),
    description = "Get the list of scheduled transactions."
  ) {
  override protected def executeTyped(input: GetScheduledTransactionsInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.emits(List[Event](ScheduledTransactionsRead(
      transactions = state.get.bankAccount.scheduledTransactions,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )))
}
