package spec

import rapid.Task
import sigil.TurnContext
import sigil.tool.model.NoResponseInput
import sigil.tool.{
  DiscoverySpec, Effect, MutationTargeting, Resolution, TextToolOutput, Tool, ToolContext, ToolGates, ToolIO, ToolName, ToolPrecondition,
  ToolPreconditionResult, ToolProfile, ToolResult, ToolSpec
}

/**
 * Test-only tool whose single precondition is never satisfied. Used by
 * [[WorkflowPreconditionGatingSpec]] to prove a workflow-step dispatch
 * runs the executor's gate pipeline: the resolution must never run and
 * the step must surface the blocked state. `ran` flips if the body
 * ever executes.
 */
case object GatedProbeTool extends Tool {
  type Input = NoResponseInput
  type Output = TextToolOutput
  val io: ToolIO[NoResponseInput, TextToolOutput] = ToolIO.derived[NoResponseInput, TextToolOutput]

  @volatile var ran: Boolean = false

  private object NeverSatisfied extends ToolPrecondition {
    val name: String = "docker-daemon"
    override def check(context: TurnContext): Task[ToolPreconditionResult] =
      Task.pure(ToolPreconditionResult.Unsatisfied("no docker daemon is running", suggestedFix = Some("start_docker")))
  }

  val spec: ToolSpec = ToolSpec(
    name = ToolName("gated_probe"),
    description = "Test-only tool with a permanently-unsatisfied precondition.",
    profile = ToolProfile(
      effect = Effect.Mutating(MutationTargeting.none),
      gates = ToolGates(preconditions = List(NeverSatisfied))
    ),
    discovery = DiscoverySpec(keywords = Set("test", "gated"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple { (_, _) =>
    ran = true
    Task.pure(TextToolOutput("RAN"))
  }
}
