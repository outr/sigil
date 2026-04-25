package bench

import fabric.Json
import fabric.define.{DefType, Definition}
import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{Tool, ToolInput, ToolName, TypedTool}

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
 * from a compile-time `RW[Input]`. `execute` is a no-op — dynamic tools
 * are only used for the "what would the model call here?" measurement.
 */
case class DynamicTool(toolName: String,
                       toolDescription: String,
                       paramsDefinition: Definition) extends TypedTool[DynamicToolInput](
  name = ToolName(toolName),
  description = toolDescription
) {
  /** Override the schema's input definition with the hand-built one
    * (the LLM sees the bench-supplied schema; the parser returns raw
    * JSON via DynamicToolInput.rw regardless). */
  override def inputDefinition: Definition = paramsDefinition

  override protected def executeTyped(input: DynamicToolInput, context: TurnContext): Stream[Event] = Stream.empty
}
