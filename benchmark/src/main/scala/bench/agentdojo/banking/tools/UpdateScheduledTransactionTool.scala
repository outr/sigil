package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.{ScheduledTransactionNotFound, ScheduledTransactionUpdated}
import fabric.rw.*
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicReference

final case class UpdateScheduledTransactionInput(@description("ID of the transaction (mandatory)") id: Int,
                                                 @description("IBAN of the recipient (optional)") recipient: Option[String] = None,
                                                 @description("Amount of the transaction (optional)") amount: Option[Double] = None,
                                                 @description("Subject of the transaction (optional)") subject: Option[String] = None,
                                                 @description("Next date of the transaction (optional)") date: Option[String] = None,
                                                 @description("Is the transaction recurring (optional)") recurring: Option[Boolean] = None)
  extends ToolInput derives RW

/** `update_scheduled_transaction` — patch a scheduled transaction by id. */
final class UpdateScheduledTransactionTool(state: AtomicReference[BankingEnvironment])
  extends TypedTool[UpdateScheduledTransactionInput](
    name = ToolName("update_scheduled_transaction"),
    description = "Update a scheduled transaction."
  ) {
  override protected def executeTyped(input: UpdateScheduledTransactionInput, context: TurnContext): rapid.Stream[Event] = {
    val before = state.get
    val matched = before.bankAccount.scheduledTransactions.exists(_.id == input.id)
    val event: Event =
      if (!matched) ScheduledTransactionNotFound(
        transactionId = input.id,
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId
      )
      else {
        state.updateAndGet { env =>
          val acct = env.bankAccount
          val updated = acct.scheduledTransactions.map { t =>
            if (t.id != input.id) t
            else t.copy(
              recipient = input.recipient.getOrElse(t.recipient),
              amount = input.amount.getOrElse(t.amount),
              subject = input.subject.getOrElse(t.subject),
              date = input.date.getOrElse(t.date),
              recurring = input.recurring.getOrElse(t.recurring)
            )
          }
          env.copy(bankAccount = acct.copy(scheduledTransactions = updated))
        }
        ScheduledTransactionUpdated(
          transactionId = input.id,
          participantId = context.caller,
          conversationId = context.conversation.id,
          topicId = context.conversation.currentTopicId
        )
      }
    rapid.Stream.emits(List(event))
  }
}
