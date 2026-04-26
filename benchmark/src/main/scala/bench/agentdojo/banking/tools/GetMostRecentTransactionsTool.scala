package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.TransactionsRead
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

final case class GetMostRecentTransactionsInput(@description("Number of transactions to return") n: Int = 100)
  extends ToolInput derives RW

/** `get_most_recent_transactions` — return the trailing `n` settled transactions. */
final class GetMostRecentTransactionsTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[GetMostRecentTransactionsInput](
    name = ToolName("get_most_recent_transactions"),
    description = "Get the list of the most recent transactions, e.g. to summarize the last n transactions."
  ) {
  override protected def executeTyped(input: GetMostRecentTransactionsInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.emits(List[Event](TransactionsRead(
      transactions = state.get.bankAccount.transactions.takeRight(input.n),
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )))
}
