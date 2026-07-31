package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.ScheduledTransactionsRead
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Freshness,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

import java.util.concurrent.atomic.AtomicReference

final case class GetScheduledTransactionsInput() extends ToolInput derives RW

/**
 * `get_scheduled_transactions` — return the scheduled-transaction list.
 */
final class GetScheduledTransactionsTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = GetScheduledTransactionsInput
  type Output = TextToolOutput

  val io: ToolIO[GetScheduledTransactionsInput, TextToolOutput] = ToolIO.derived[GetScheduledTransactionsInput, TextToolOutput]

  override val name: ToolName = ToolName("get_scheduled_transactions")
  override val description: String = "Get the list of scheduled transactions."

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("bank", "transactions", "scheduled", "list"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: GetScheduledTransactionsInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val transactions = state.get.bankAccount.scheduledTransactions
    context.emit(ScheduledTransactionsRead(
      transactions = transactions,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId =
        context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput(s"${transactions.size} scheduled transaction(s)")))
  }
}
