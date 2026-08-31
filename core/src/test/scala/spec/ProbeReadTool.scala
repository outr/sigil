package spec

import rapid.Task
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolContext,
  ToolIO,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

/**
 * Probe tool for the intra-turn accumulation specs: echoes its `probe`
 * token back in the result text so a spec can assert exactly which
 * tool results reached the model's rendered prompt on a given
 * iteration.
 *
 * Declared `Mutating` so the turn-scoped read cache never serves a
 * repeat from cache — every call produces a real invoke + result event,
 * which is what the accumulation assertions measure.
 */
case object ProbeReadTool extends Tool {
  type Input = ProbeReadInput
  type Output = TextToolOutput

  val io: ToolIO[ProbeReadInput, TextToolOutput] = ToolIO.derived[ProbeReadInput, TextToolOutput]

  override val name: ToolName = ToolName("probe_read")
  override val description: String = "Reads a probe value and returns it. Test fixture."

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("test", "probe_read"))
  )

  /**
   * Result text for `probe` — the exact string specs search the
   * rendered prompt for.
   */
  def resultTextFor(probe: String): String = s"probe-result:$probe"

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(run)

  private def run(input: ProbeReadInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(resultTextFor(input.probe))))
}
