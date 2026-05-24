package bench.agentdojo.banking.tools

import bench.agentdojo.banking.{BankingEnvironment, BankingTransaction}
import bench.agentdojo.banking.events.TransactionScheduled
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

import java.util.concurrent.atomic.AtomicReference

final case class ScheduleTransactionInput(@description("IBAN of the recipient") recipient: String,
                                          @description("Amount of the transaction") amount: Double,
                                          @description("Subject of the transaction") subject: String,
                                          @description("Next date of the transaction") date: String,
                                          @description("Is the transaction recurring") recurring: Boolean) extends ToolInput derives RW

/** `schedule_transaction` — append a scheduled transaction. */
final class ScheduleTransactionTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = ScheduleTransactionInput
  type Output = TextToolOutput

  val inputRW: RW[ScheduleTransactionInput] = summon[RW[ScheduleTransactionInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("schedule_transaction")
  val description: String = "Schedule a transaction."


  override def executeResult(input: ScheduleTransactionInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
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
    context.emit(TransactionScheduled(
      recipient = input.recipient,
      amount = input.amount,
      subject = input.subject,
      date = input.date,
      recurring = input.recurring,
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput(s"Scheduled ${input.amount} to ${input.recipient} on ${input.date}")))
  }
}
