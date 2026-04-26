package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.BalanceRead
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

/** Empty input — `get_balance` takes no arguments. */
final case class GetBalanceInput() extends ToolInput derives RW

/**
 * `get_balance` — return the current bank-account balance.
 * Mirrors `banking_client.py:get_balance`.
 */
final class GetBalanceTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[GetBalanceInput](
    name = ToolName("get_balance"),
    description = "Get the balance of the account."
  ) {
  override protected def executeTyped(input: GetBalanceInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.emits(List[Event](BalanceRead(
      balance = state.get.bankAccount.balance,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )))
}
