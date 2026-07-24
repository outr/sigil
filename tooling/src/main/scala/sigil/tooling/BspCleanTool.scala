package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.BspCleanResult

case class BspCleanInput(projectRoot: String,
                         targets: List[String] = Nil)
  extends ToolInput derives RW

/**
 * Clean the build cache for the given targets. Useful when the agent
 * suspects an incremental compiler artifact is stale. Empty
 * `targets` cleans every workspace target.
 */
final class BspCleanTool(val manager: BspManager) extends Tool with sigil.tool.DestructiveExternalTool with BspToolSupport {
  type Input = BspCleanInput
  type Output = BspCleanResult
  val inputRW = summon[RW[BspCleanInput]]
  val outputRW = summon[RW[BspCleanResult]]

  val name = ToolName("bsp_clean")
  val description =
    """Clean the build cache for the given targets via the BSP server.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty cleans every workspace target.""".stripMargin
  override val keywords = Set("bsp", "clean", "clean cache", "clear build", "wipe build", "reset")

  override def executeOutput(input: BspCleanInput, context: ToolContext): Task[BspCleanResult] =
    withTargets[BspCleanResult](
      input.projectRoot,
      context,
      input.targets,
      onError = _ => BspCleanResult(input.projectRoot, 0, cleaned = false),
      emptyResult = BspCleanResult(input.projectRoot, 0, cleaned = false)
    ) { (session, targets) =>
      session.cleanCache(targets).map { result =>
        val ok = Option(result.getCleaned).map(_.booleanValue).getOrElse(false)
        BspCleanResult(input.projectRoot, targets.size, cleaned = ok)
      }
    }
}
