package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.{ScheduledTransactionNotFound, ScheduledTransactionUpdated}
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
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

final case class UpdateScheduledTransactionInput(@description("ID of the transaction (mandatory)") id: Int,
                                                 @description("IBAN of the recipient (optional)") recipient: Option[String] = None,
                                                 @description("Amount of the transaction (optional)") amount: Option[Double] = None,
                                                 @description("Subject of the transaction (optional)") subject: Option[String] = None,
                                                 @description("Next date of the transaction (optional)") date: Option[String] = None,
                                                 @description("Is the transaction recurring (optional)") recurring: Option[Boolean] = None)
  extends ToolInput derives RW

/**
 * `update_scheduled_transaction` — patch a scheduled transaction by id.
 */
final class UpdateScheduledTransactionTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = UpdateScheduledTransactionInput
  type Output = TextToolOutput

  val io: ToolIO[UpdateScheduledTransactionInput, TextToolOutput] = ToolIO.derived[UpdateScheduledTransactionInput, TextToolOutput]

  override val name: ToolName = ToolName("update_scheduled_transaction")
  override val description: String = "Update a scheduled transaction."

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("bank", "update", "scheduled", "transaction"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: UpdateScheduledTransactionInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val before = state.get
    val matched = before.bankAccount.scheduledTransactions.exists(_.id == input.id)
    if (!matched) {
      context.emit(ScheduledTransactionNotFound(
        transactionId = input.id,
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId =
          context.conversation.currentTopicId
      )).map(_ => ToolResult.failure(s"No scheduled transaction with id ${input.id}."))
    } else {
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
      context.emit(ScheduledTransactionUpdated(
        transactionId = input.id,
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId =
          context.conversation.currentTopicId
      )).map(_ => ToolResult.Success(TextToolOutput(s"Updated scheduled transaction ${input.id}.")))
    }
  }
}
