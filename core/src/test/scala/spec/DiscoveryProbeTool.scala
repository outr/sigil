package spec

import fabric.rw.*
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
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

/**
 * Input for [[DiscoveryProbeTool]] — `count` controls how many items it emits.
 */
case class DiscoveryProbeInput(count: Int) extends ToolInput derives RW

/**
 * Test-only discovery tool: emits `count` newline-separated items — the shape
 * a real discovery tool (`grep`/`glob` in `FilesWithMatches` mode) produces.
 * Lets a workflow prove discovery-AS-A-STAGE (a `Loop` consuming this step's
 * text output, no hand-built array) without standing up a live filesystem.
 */
case object DiscoveryProbeTool extends Tool {
  type Input = DiscoveryProbeInput
  type Output = TextToolOutput
  val io: ToolIO[DiscoveryProbeInput, TextToolOutput] = ToolIO.derived[DiscoveryProbeInput, TextToolOutput]
  override val name = ToolName("discovery_probe")
  override val description = "Emits N newline-separated items — a discovery-step stand-in for workflow tests."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Pure)),
    discovery = DiscoverySpec(keywords = Set("test", "discovery_probe"))
  )
  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DiscoveryProbeInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    // Items are wide (path-shaped) so a modest count still exceeds the inline
    // cap — exercises the overflow path without thousands of loop iterations.
    Task.pure(ToolResult.Success(TextToolOutput(
      (1 to input.count).map(i => s"core/src/main/scala/sigil/generated/item-$i-" + ("x" * 150) + ".scala").mkString("\n")
    )))
}
