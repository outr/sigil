package bench.agentdojo.banking.tools

import bench.agentdojo.banking.{BankingEnvironment, BankingTransaction}
import bench.agentdojo.banking.events.MoneyTransferred
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

import java.util.concurrent.atomic.AtomicReference

final case class SendMoneyInput(@description("IBAN of the recipient") recipient: String,
                                @description("Amount of the transaction") amount: Double,
                                @description("Subject of the transaction") subject: String,
                                @description("Date of the transaction") date: String) extends ToolInput derives RW

/** `send_money` — append a one-shot transaction (sender = user IBAN). */
final class SendMoneyTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = SendMoneyInput
  type Output = TextToolOutput

  val inputRW: RW[SendMoneyInput] = summon[RW[SendMoneyInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  override val name: ToolName = ToolName("send_money")
  override val description: String = "Sends a transaction to the recipient."

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("bank", "send", "money", "transaction", "transfer"))
  )

  override def executeResult(input: SendMoneyInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    state.updateAndGet { env =>
      val acct = env.bankAccount
      val tx = BankingTransaction(
        id = acct.nextId,
        sender = acct.iban,
        recipient = input.recipient,
        amount = input.amount,
        subject = input.subject,
        date = input.date,
        recurring = false
      )
      env.copy(bankAccount = acct.copy(transactions = acct.transactions :+ tx))
    }
    context.emit(MoneyTransferred(
      recipient = input.recipient,
      amount = input.amount,
      subject = input.subject,
      date = input.date,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput(s"Sent ${input.amount} to ${input.recipient}")))
  }
}
