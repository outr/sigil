package spec

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

/** Empty input — `get_magic_number` takes no arguments. */
final case class GetMagicNumberInput() extends ToolInput derives RW

/**
 * Test-only tool used by [[MultiStepToolFlowSpec]] to demonstrate the
 * multi-step tool-flow gap. Returns the literal string "42" as its
 * typed result.
 *
 * The point: this is the natural shape an app builder would write
 * for a data-returning tool. The flaw the spec demonstrates is that
 * the agent's outer self-loop must re-trigger so the agent gets the
 * chance to read the result and compose a `respond` call.
 */
case object GetMagicNumberTool extends Tool {
  type Input  = GetMagicNumberInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[GetMagicNumberInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("get_magic_number")
  val description = "Returns the magic number. Call this first, then tell the user what number you got."

  override def executeResult(input: GetMagicNumberInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput("42")))
}
