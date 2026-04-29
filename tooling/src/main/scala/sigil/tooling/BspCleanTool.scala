package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class BspCleanInput(projectRoot: String,
                         targets: List[String] = Nil) extends ToolInput derives RW

/**
 * Clean the build cache for the given targets. Useful when the agent
 * suspects an incremental compiler artifact is stale. Empty
 * `targets` cleans every workspace target.
 */
final class BspCleanTool(val manager: BspManager) extends TypedTool[BspCleanInput](
  name = ToolName("bsp_clean"),
  description =
    """Clean the build cache for the given targets via the BSP server.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty cleans every workspace target.
      |Returns whether the clean was acknowledged.""".stripMargin,
  examples = List(
    ToolExample(
      "clean every target",
      BspCleanInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspCleanInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure("No targets to clean.")
        else session.cleanCache(targets).map { result =>
          val ok = Option(result.getCleaned).map(_.booleanValue).getOrElse(false)
          if (ok) s"Cleaned ${targets.size} target(s)."
          else s"Clean reported not-cleaned across ${targets.size} target(s)."
        }
      }
    }
}
