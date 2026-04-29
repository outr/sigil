package sigil.tooling

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class BspReloadInput(projectRoot: String) extends ToolInput derives RW

/**
 * Reload the build server's project model — the BSP equivalent of
 * sbt's `reload`. Use after the agent edits build files (`build.sbt`,
 * `Cargo.toml`, etc.) so subsequent compile/test calls see the new
 * targets / dependencies.
 */
final class BspReloadTool(val manager: BspManager) extends TypedTool[BspReloadInput](
  name = ToolName("bsp_reload"),
  description =
    """Reload the build server's project model (after build-file edits).
      |
      |`projectRoot` selects the persisted BspBuildConfig.""".stripMargin,
  examples = List(
    ToolExample(
      "reload after editing build.sbt",
      BspReloadInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspReloadInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      session.reload.map(_ => s"Reloaded build for ${input.projectRoot}.")
    }
}
