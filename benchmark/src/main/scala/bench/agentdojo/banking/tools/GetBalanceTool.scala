package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.BalanceRead
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

/**
 * Empty input — `get_balance` takes no arguments.
 */
final case class GetBalanceInput() extends ToolInput derives RW

/**
 * `get_balance` — return the current bank-account balance.
 * Mirrors `banking_client.py:get_balance`.
 */
final class GetBalanceTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = GetBalanceInput
  type Output = TextToolOutput

  val io: ToolIO[GetBalanceInput, TextToolOutput] = ToolIO.derived[GetBalanceInput, TextToolOutput]

  override val name: ToolName = ToolName("get_balance")
  override val description: String = "Get the balance of the account."

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("bank", "account", "balance", "get"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: GetBalanceInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val balance = state.get.bankAccount.balance
    context.emit(BalanceRead(
      balance = balance,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId =
        context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput(balance.toString)))
  }
}
