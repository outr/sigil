package spec

import fabric.rw.*
import rapid.Task
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTarget,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolContext,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

final case class MutatingSpecInput(step: String, target: Option[String] = None) extends ToolInput derives RW

/**
 * Test-only destructive-annotated tool: a stand-in for `edit_file` /
 * `write_file`-class work. Checkpoint specs invoke it with varying
 * `step` values so its successful settles register as mechanical
 * progress (window mutations) without tripping the identical-call
 * stall detector.
 */
case object MutatingSpecTool extends Tool {
  type Input = MutatingSpecInput
  type Output = TextToolOutput
  val io: ToolIO[MutatingSpecInput, TextToolOutput] = ToolIO.derived[MutatingSpecInput, TextToolOutput]
  override val name = ToolName("mutate_spec_state")
  override val description = "Test-only state-changing tool; applies the named step."

  /**
   * Target defaults to the step itself — each distinct step models a
   * distinct file, like a bulk sweep; churn specs pin `target` to
   * model re-editing one file.
   */
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(
      effect = Effect.Destructive(
        target = MutationTargeting.typed[MutatingSpecInput](i => Some(MutationTarget(i.target.getOrElse(i.step)))),
        consequence = "DESTRUCTIVE."
      )
    ),
    discovery = DiscoverySpec(keywords = Set("mutate", "test"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: MutatingSpecInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(s"applied ${input.step}")))
}
