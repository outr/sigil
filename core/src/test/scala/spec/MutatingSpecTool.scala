package spec

import fabric.rw.*
import rapid.Task
import sigil.tool.{DestructiveExternalTool, TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolResult}

final case class MutatingSpecInput(step: String) extends ToolInput derives RW

/**
 * Test-only destructive-annotated tool: a stand-in for `edit_file` /
 * `write_file`-class work. Checkpoint specs invoke it with varying
 * `step` values so its successful settles register as mechanical
 * progress (window mutations) without tripping the identical-call
 * stall detector.
 */
case object MutatingSpecTool extends Tool with DestructiveExternalTool {
  type Input  = MutatingSpecInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[MutatingSpecInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("mutate_spec_state")
  val description = "Test-only state-changing tool; applies the named step."
  override val keywords: Set[String] = Set("mutate", "test")

  override def executeResult(input: MutatingSpecInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(s"applied ${input.step}")))
}
