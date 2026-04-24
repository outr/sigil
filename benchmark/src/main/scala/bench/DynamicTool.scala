package bench

import fabric.Json
import fabric.define.{DefType, Definition}
import fabric.rw.*
import lightdb.id.Id
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{Tool, ToolInput, ToolName, ToolSchema}

/**
 * Tool input that carries arbitrary JSON args without a compile-time
 * case-class shape. Used by tool-use benchmarks (BFCL, τ-bench, etc.)
 * that need to drive sigil's provider with thousands of unrelated tool
 * schemas defined at dataset-load time.
 *
 * The RW round-trip just wraps/unwraps the full JSON object — sigil's
 * tool-call accumulator will call `RW.write(argsJson)` which yields a
 * `DynamicToolInput` whose `args` field is the model's raw output.
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
 * from a compile-time `RW[Input]`. `execute` is a no-op — dynamic
 * tools are only used for the "what would the model call here?"
 * measurement; the benchmark runner captures the parsed
 * [[DynamicToolInput]] from the `ToolCallComplete` event and
 * compares it to ground truth.
 */
case class DynamicTool(toolName: String,
                       toolDescription: String,
                       paramsDefinition: Definition) extends Tool[DynamicToolInput] {
  override protected def uniqueName: String = toolName
  override protected def description: String = toolDescription

  // Override the lazy schema to use the hand-built definition rather
  // than the one that would come from `summon[RW[DynamicToolInput]]`
  // (which is `Definition.json` — deliberately permissive, so the
  // bench runner can stand the model's actual output back up as a
  // Json object regardless of what schema we emit to the LLM).
  override lazy val schema: ToolSchema[DynamicToolInput] = ToolSchema(
    id = Id[ToolSchema[DynamicToolInput]](toolName),
    name = ToolName(toolName),
    description = toolDescription,
    input = paramsDefinition,
    examples = Nil
  )

  override def execute(input: DynamicToolInput, context: TurnContext): Stream[Event] = Stream.empty
}
