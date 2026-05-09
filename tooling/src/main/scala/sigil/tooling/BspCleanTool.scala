package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.BspCleanResult

case class BspCleanInput(projectRoot: String,
                         targets: List[String] = Nil) extends ToolInput derives RW

/**
 * Clean the build cache for the given targets. Useful when the agent
 * suspects an incremental compiler artifact is stale. Empty
 * `targets` cleans every workspace target.
 */
final class BspCleanTool(val manager: BspManager) extends TypedOutputTool[BspCleanInput, BspCleanResult](
  name = ToolName("bsp_clean"),
  description =
    """Clean the build cache for the given targets via the BSP server.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty cleans every workspace target.""".stripMargin,
  keywords = Set("bsp", "clean", "clean cache", "clear build", "wipe build", "reset"),
  examples = List(
    ToolExample(
      "clean every target",
      BspCleanInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspCleanInput, context: TurnContext): Task[BspCleanResult] =
    withSessionTyped[BspCleanResult](
      input.projectRoot, context,
      onError = msg => throw new RuntimeException(msg)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure(BspCleanResult(input.projectRoot, 0, cleaned = false))
        else session.cleanCache(targets).map { result =>
          val ok = Option(result.getCleaned).map(_.booleanValue).getOrElse(false)
          BspCleanResult(input.projectRoot, targets.size, cleaned = ok)
        }
      }
    }
}
