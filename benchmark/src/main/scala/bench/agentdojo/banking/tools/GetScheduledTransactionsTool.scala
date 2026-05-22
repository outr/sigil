package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.ScheduledTransactionsRead
import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

import java.util.concurrent.atomic.AtomicReference

final case class GetScheduledTransactionsInput() extends ToolInput derives RW

/** `get_scheduled_transactions` — return the scheduled-transaction list. */
final class GetScheduledTransactionsTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = GetScheduledTransactionsInput
  type Output = TextToolOutput

  val inputRW: RW[GetScheduledTransactionsInput] = summon[RW[GetScheduledTransactionsInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("get_scheduled_transactions")
  val description: String = "Get the list of scheduled transactions."

  override def paginate: Boolean = false

  override def executeResult(input: GetScheduledTransactionsInput, context: TurnContext): Task[ToolResult[TextToolOutput]] = {
    val transactions = state.get.bankAccount.scheduledTransactions
    context.emit(ScheduledTransactionsRead(
      transactions = transactions,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput(s"${transactions.size} scheduled transaction(s)")))
  }
}
