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
import scala.concurrent.duration.*

/**
 * A live-data read: [[Freshness.Volatile]], the declaration any tool
 * fronting a system whose rows change under it carries (an ERP search,
 * an inventory level, a queue depth).
 *
 * `delays` makes a resolution slow enough that siblings of one parallel
 * batch settle at different times, which is the field timing every
 * instant-settling fixture skips.
 */
case object LiveProbeReadTool extends Tool {
  type Input = ProbeReadInput
  type Output = TextToolOutput

  val io: ToolIO[ProbeReadInput, TextToolOutput] = ToolIO.derived[ProbeReadInput, TextToolOutput]

  override val name: ToolName = ToolName("live_probe_read")
  override val description: String = "Reads a live probe value that changes under the caller. Test fixture."

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(keywords = Set("test", "live_probe_read"))
  )

  val executions: AtomicInteger = new AtomicInteger(0)

  /** Per-probe resolution delay, so siblings of a batch settle apart. */
  @volatile var delays: Map[String, FiniteDuration] = Map.empty

  def resultTextFor(probe: String): String = s"live-probe-result:$probe"

  def reset(): Unit = {
    executions.set(0)
    delays = Map.empty
  }

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(run)

  private def run(input: ProbeReadInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val wait = delays.getOrElse(input.probe, Duration.Zero)
    val body = Task {
      executions.incrementAndGet()
      ToolResult.Success(TextToolOutput(resultTextFor(input.probe)))
    }
    if (wait > Duration.Zero) Task.sleep(wait).flatMap(_ => body) else body
  }
}
