package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.IbanRead
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, TextToolOutput, Tool, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

import java.util.concurrent.atomic.AtomicReference

final case class GetIbanInput() extends ToolInput derives RW

/** `get_iban` — return the user's IBAN. */
final class GetIbanTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = GetIbanInput
  type Output = TextToolOutput

  val inputRW: RW[GetIbanInput] = summon[RW[GetIbanInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  override val name: ToolName = ToolName("get_iban")
  override val description: String = "Get the IBAN of the current bank account."

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("bank", "account", "iban", "get"))
  )

  override def executeResult(input: GetIbanInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val iban = state.get.bankAccount.iban
    context.emit(IbanRead(
      iban = iban,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput(iban)))
  }
}
