package bench

import fabric.Json
import fabric.define.{DefType, Definition}
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

/**
 * Tool input that carries arbitrary JSON args without a compile-time
 * case-class shape. Used by tool-use benchmarks (BFCL, τ-bench, etc.)
 * that need to drive sigil's provider with thousands of unrelated tool
 * schemas defined at dataset-load time.
 */
case class DynamicToolInput(args: Json) extends ToolInput

object DynamicToolInput {
  given rw: RW[DynamicToolInput] = new RW[DynamicToolInput] {
    override def read(t: DynamicToolInput): Json = t.args
    override def write(json: Json): DynamicToolInput = DynamicToolInput(json)
    override def definition: Definition = Definition(DefType.Json)
  }
}

/**
 * Sigil [[Tool]] with a user-supplied schema instead of one derived
 * from a compile-time `RW[Input]`. Execution is a no-op — dynamic tools
 * are only used for the "what would the model call here?" measurement.
 */
case class DynamicTool(toolName: String,
                       toolDescription: String,
                       paramsDefinition: Definition)
  extends Tool {
  type Input = DynamicToolInput
  type Output = TextToolOutput

  val inputRW: RW[DynamicToolInput] = summon[RW[DynamicToolInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName(toolName)
  val description: String = toolDescription

  /**
   * Override the schema's input definition with the hand-built one
   * (the LLM sees the bench-supplied schema; the parser returns raw
   * JSON via DynamicToolInput.rw regardless).
   */
  override def inputDefinition: Definition = paramsDefinition

  override def executeResult(input: DynamicToolInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput("")))
}
