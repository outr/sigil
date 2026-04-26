package bench.agentdojo.banking.tools

import bench.agentdojo.banking.{BankingEnvironment, BankingTransaction}
import bench.agentdojo.banking.events.TransactionScheduled
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

final case class ScheduleTransactionInput(@description("IBAN of the recipient") recipient: String,
                                          @description("Amount of the transaction") amount: Double,
                                          @description("Subject of the transaction") subject: String,
                                          @description("Next date of the transaction") date: String,
                                          @description("Is the transaction recurring") recurring: Boolean) extends ToolInput derives RW

/** `schedule_transaction` — append a scheduled transaction. */
final class ScheduleTransactionTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[ScheduleTransactionInput](
    name = ToolName("schedule_transaction"),
    description = "Schedule a transaction."
  ) {
  override protected def executeTyped(input: ScheduleTransactionInput, context: TurnContext): rapid.Stream[Event] = {
    state.updateAndGet { env =>
      val acct = env.bankAccount
      val tx = BankingTransaction(
        id = acct.nextId,
        sender = acct.iban,
        recipient = input.recipient,
        amount = input.amount,
        subject = input.subject,
        date = input.date,
        recurring = input.recurring
      )
      env.copy(bankAccount = acct.copy(scheduledTransactions = acct.scheduledTransactions :+ tx))
    }
    rapid.Stream.emits(List[Event](TransactionScheduled(
      recipient = input.recipient,
      amount = input.amount,
      subject = input.subject,
      date = input.date,
      recurring = input.recurring,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )))
  }
}
