package spec

import rapid.Task
import sigil.tool.model.NoResponseInput
import sigil.tool.{ConsentSpec, DiscoverySpec, Effect, MutationTargeting, Resolution, TextToolOutput, Tool, ToolGates, ToolIO, ToolName, ToolProfile, ToolSpec}

/** Test-only consent-gated tool. Used by [[WorkflowEmittedEventsSpec]] to
  * prove a workflow step's `ctx.emit`-ed events are actually published:
  * without the persisted `ToolApproval` from a preceding `record_consent`
  * step, this tool's gate refuses and `ran` stays `false`. */
case object ConsentProbeTool extends Tool {
  type Input  = NoResponseInput
  type Output = TextToolOutput
  val io: ToolIO[NoResponseInput, TextToolOutput] = ToolIO.derived[NoResponseInput, TextToolOutput]

  @volatile var ran: Boolean = false

  val spec: ToolSpec = ToolSpec(
    name = ToolName("consent_probe"),
    description = "Test-only consent-gated tool.",
    profile = ToolProfile(
      effect = Effect.Mutating(MutationTargeting.none),
      gates = ToolGates(consent = Some(ConsentSpec("May I run the consent probe?")))
    ),
    discovery = DiscoverySpec(keywords = Set("test", "consent"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple { (_, _) =>
    ran = true
    Task.pure(TextToolOutput("RAN"))
  }
}
