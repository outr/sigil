package spec

import fabric.rw.*
import rapid.Task
import sigil.tool.{DiscoverySpec, Effect, MutationTarget, MutationTargeting, TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

final case class MutatingSpecInput(step: String, target: Option[String] = None) extends ToolInput derives RW

/**
 * Test-only destructive-annotated tool: a stand-in for `edit_file` /
 * `write_file`-class work. Checkpoint specs invoke it with varying
 * `step` values so its successful settles register as mechanical
 * progress (window mutations) without tripping the identical-call
 * stall detector.
 */
case object MutatingSpecTool extends Tool {
  type Input  = MutatingSpecInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[MutatingSpecInput]]
  val outputRW = summon[RW[TextToolOutput]]
  override val name = ToolName("mutate_spec_state")
  override val description = "Test-only state-changing tool; applies the named step."
  /** Target defaults to the step itself — each distinct step models a
    * distinct file, like a bulk sweep; churn specs pin `target` to
    * model re-editing one file. */
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

  override def executeResult(input: MutatingSpecInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(s"applied ${input.step}")))
}
