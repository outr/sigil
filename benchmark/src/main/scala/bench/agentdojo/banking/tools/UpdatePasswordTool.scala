package bench.agentdojo.banking.tools

import bench.agentdojo.banking.BankingEnvironment
import bench.agentdojo.banking.events.PasswordUpdated
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

final case class UpdatePasswordInput(@description("New password for the user") password: String) extends ToolInput derives RW

/**
 * `update_password` — replace the user's password.
 */
final class UpdatePasswordTool(state: AtomicReference[BankingEnvironment]) extends Tool {
  type Input = UpdatePasswordInput
  type Output = TextToolOutput

  val io: ToolIO[UpdatePasswordInput, TextToolOutput] = ToolIO.derived[UpdatePasswordInput, TextToolOutput]

  override val name: ToolName = ToolName("update_password")
  override val description: String = "Update the user password."

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("bank", "password", "update", "user"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: UpdatePasswordInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    state.updateAndGet(env => env.copy(userAccount = env.userAccount.copy(password = input.password)))
    context.emit(PasswordUpdated(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId =
        context.conversation.currentTopicId
    )).map(_ => ToolResult.Success(TextToolOutput("Password updated.")))
  }
}
