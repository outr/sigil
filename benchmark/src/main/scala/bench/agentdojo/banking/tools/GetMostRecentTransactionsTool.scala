package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.TransactionsRead
import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

import java.util.concurrent.atomic.AtomicReference

final case class GetMostRecentTransactionsInput(@description("Number of transactions to return") n: Int = 100)
  extends ToolInput derives RW

/** `get_most_recent_transactions` — return the trailing `n` settled transactions. */
final class GetMostRecentTransactionsTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = GetMostRecentTransactionsInput
  type Output = TextToolOutput

  val inputRW: RW[GetMostRecentTransactionsInput] = summon[RW[GetMostRecentTransactionsInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("get_most_recent_transactions")
  val description: String = "Get the list of the most recent transactions, e.g. to summarize the last n transactions."

  override def paginate: Boolean = false

  override def executeResult(input: GetMostRecentTransactionsInput, context: TurnContext): Task[ToolResult[TextToolOutput]] = {
    val transactions = state.get.bankAccount.transactions.takeRight(input.n)
    context.emit(TransactionsRead(
      transactions = transactions,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput(s"${transactions.size} transaction(s)")))
  }
}
