package bench.agentdojo.banking.tools

import bench.agentdojo.banking.{BankingEnvironment, BankingTransaction}
import bench.agentdojo.banking.events.MoneyTransferred
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

final case class SendMoneyInput(@description("IBAN of the recipient") recipient: String,
                                @description("Amount of the transaction") amount: Double,
                                @description("Subject of the transaction") subject: String,
                                @description("Date of the transaction") date: String) extends ToolInput derives RW

/** `send_money` — append a one-shot transaction (sender = user IBAN). */
final class SendMoneyTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[SendMoneyInput](
    name = ToolName("send_money"),
    description = "Sends a transaction to the recipient."
  ) {
  override protected def executeTyped(input: SendMoneyInput, context: TurnContext): rapid.Stream[Event] = {
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
    rapid.Stream.emits(List[Event](MoneyTransferred(
      recipient = input.recipient,
      amount = input.amount,
      subject = input.subject,
      date = input.date,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )))
  }
}
