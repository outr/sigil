package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.BalanceRead
import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

import java.util.concurrent.atomic.AtomicReference

/** Empty input — `get_balance` takes no arguments. */
final case class GetBalanceInput() extends ToolInput derives RW

/**
 * `get_balance` — return the current bank-account balance.
 * Mirrors `banking_client.py:get_balance`.
 */
final class GetBalanceTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = GetBalanceInput
  type Output = TextToolOutput

  val inputRW: RW[GetBalanceInput] = summon[RW[GetBalanceInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("get_balance")
  val description: String = "Get the balance of the account."


  override def executeResult(input: GetBalanceInput, context: TurnContext): Task[ToolResult[TextToolOutput]] = {
    val balance = state.get.bankAccount.balance
    context.emit(BalanceRead(
      balance = balance,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput(balance.toString)))
  }
}
