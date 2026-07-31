package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{BspBuildTarget, BspListTargetsResult}

case class BspListTargetsInput(projectRoot: String) extends ToolInput derives RW

/**
 * List every build target the BSP server knows about — sub-projects,
 * test configurations, etc. The agent uses this to discover the
 * right target id before calling `bsp_compile` / `bsp_test` / etc.
 * with an explicit list.
 */
final class BspListTargetsTool(val manager: BspManager) extends Tool with BspToolSupport {
  type Input = BspListTargetsInput
  type Output = BspListTargetsResult
  val io: ToolIO[BspListTargetsInput, BspListTargetsResult] = ToolIO.derived[BspListTargetsInput, BspListTargetsResult]

  override val name = ToolName("bsp_list_targets")
  override val description =
    """List every build target advertised by the project's BSP server.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |Returns each target's URI, display name, language tags, and capabilities (canCompile / canTest / canRun / canDebug).""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set(
        "bsp",
        "targets",
        "list targets",
        "build targets",
        "modules",
        "examine",
        "inspect",
        "scala",
        "sbt",
        "project",
        "build"
      ),
      toolchain = Some("bsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: BspListTargetsInput, context: ToolContext): Task[BspListTargetsResult] =
    withSessionTyped[BspListTargetsResult](
      input.projectRoot,
      context,
      onError = msg => BspListTargetsResult(input.projectRoot, Nil, error = Some(msg))
    ) { session =>
      session.workspaceBuildTargets.map { targets =>
        BspListTargetsResult(
          projectRoot = input.projectRoot,
          targets = targets.map(BspBuildTarget.fromBsp4j)
        )
      }
    }
}
