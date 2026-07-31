package bench

import fabric.Json
import fabric.define.{DefType, Definition}
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Freshness,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

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
case class DynamicTool(toolName: String, toolDescription: String, paramsDefinition: Definition) extends Tool {
  type Input = DynamicToolInput
  type Output = TextToolOutput

  override val name: ToolName = ToolName.parse(toolName).fold(sys.error, identity)
  override val description: String = toolDescription

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("bench", toolName))
  )

  /**
   * The bench-supplied schema over a typed raw-JSON input; the probe
   * round-trip is trivially satisfied because DynamicToolInput's RW
   * accepts any JSON.
   */
  val io: ToolIO[DynamicToolInput, TextToolOutput] =
    ToolIO.withSchema[DynamicToolInput, TextToolOutput](paramsDefinition)

  protected def resolve: Resolution[Input, Output] =
    Resolution.Explicit((_, _) => Task.pure(ToolResult.Success(TextToolOutput(""))))
}
