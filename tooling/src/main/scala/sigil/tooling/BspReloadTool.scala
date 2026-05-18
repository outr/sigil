package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.BspReloadResult

case class BspReloadInput(projectRoot: String) extends ToolInput derives RW

/**
 * Reload the build server's project model — the BSP equivalent of
 * sbt's `reload`. Use after the agent edits build files (`build.sbt`,
 * `Cargo.toml`, etc.) so subsequent compile/test calls see the new
 * targets / dependencies.
 */
final class BspReloadTool(val manager: BspManager) extends TypedOutputTool[BspReloadInput, BspReloadResult](
  name = ToolName("bsp_reload"),
  description =
    """Reload the build server's project model (after build-file edits).
      |
      |`projectRoot` selects the persisted BspBuildConfig.""".stripMargin,
  keywords = Set("bsp", "reload", "refresh", "rebuild", "reinitialise", "rescan")
) with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: BspReloadInput, context: TurnContext): Task[BspReloadResult] =
    withSessionTyped[BspReloadResult](
      input.projectRoot, context,
      onError = _ => BspReloadResult(input.projectRoot)
    ) { session =>
      session.reload.map(_ => BspReloadResult(input.projectRoot))
    }
}
