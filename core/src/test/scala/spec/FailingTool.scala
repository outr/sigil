package spec

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}
import sigil.tool.ToolContext

/**
 * Test-only tool that unconditionally throws when executed. Used by
 * [[LlamaCppWorkerSpec]] to verify a worker's tool-dispatch path
 * surfaces tool failures cleanly: the workflow must reach a
 * terminal status (Failure) rather than hang.
 */
case object FailingTool extends Tool {
  type Input = FailingToolInput
  type Output = TextToolOutput
  val io: ToolIO[FailingToolInput, TextToolOutput] = ToolIO.derived[FailingToolInput, TextToolOutput]
  override val name = ToolName("intentional_failure")
  override val description = "Test-only tool that always throws an exception when called."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("fail", "test", "error"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: FailingToolInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.error(new RuntimeException("intentional failure for worker error-handling test"))
}
