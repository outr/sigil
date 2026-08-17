package spec

import rapid.Task
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Freshness,
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

import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only sibling of [[ProbeReadTool]]: a repeat call with identical
 * args is answered from the turn-scoped read cache instead of running
 * again, which is the shape a model re-issuing the same lookup produces
 * in the field. `executions` counts the calls that actually ran, so a
 * spec can tell a served answer from a fresh one.
 */
case object CachedProbeReadTool extends Tool {
  type Input = ProbeReadInput
  type Output = TextToolOutput

  val io: ToolIO[ProbeReadInput, TextToolOutput] = ToolIO.derived[ProbeReadInput, TextToolOutput]

  override val name: ToolName = ToolName("cached_probe_read")
  override val description: String = "Reads a probe value and returns it, cached for the turn. Test fixture."

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("test", "cached_probe_read"))
  )

  val executions: AtomicInteger = new AtomicInteger(0)

  def resultTextFor(probe: String): String = s"cached-probe-result:$probe"

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(run)

  private def run(input: ProbeReadInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = Task {
    executions.incrementAndGet()
    ToolResult.Success(TextToolOutput(resultTextFor(input.probe)))
  }
}
