package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Tool, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.BspReloadResult

case class BspReloadInput(projectRoot: String) extends ToolInput derives RW

/**
 * Reload the build server's project model — the BSP equivalent of
 * sbt's `reload`. Use after the agent edits build files (`build.sbt`,
 * `Cargo.toml`, etc.) so subsequent compile/test calls see the new
 * targets / dependencies.
 */
final class BspReloadTool(val manager: BspManager) extends Tool
  with BspToolSupport {
  type Input  = BspReloadInput
  type Output = BspReloadResult
  val inputRW  = summon[RW[BspReloadInput]]
  val outputRW = summon[RW[BspReloadResult]]

  override val name = ToolName("bsp_reload")
  override val description =
    """Reload the build server's project model (after build-file edits).
      |
      |`projectRoot` selects the persisted BspBuildConfig.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(
      keywords = Set("bsp", "reload", "refresh", "rebuild", "reinitialise", "rescan"),
      toolchain = Some("bsp")
    )
  )

  override def executeOutput(input: BspReloadInput, context: ToolContext): Task[BspReloadResult] =
    withSessionTyped[BspReloadResult](
      input.projectRoot, context,
      onError = msg => BspReloadResult(input.projectRoot, error = Some(msg))
    ) { session =>
      session.reload.map(_ => BspReloadResult(input.projectRoot))
    }
}
