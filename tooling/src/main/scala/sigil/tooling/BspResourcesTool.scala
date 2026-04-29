package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class BspResourcesInput(projectRoot: String,
                             targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List resource directories / files for the given targets — non-code
 * assets that ship in the build (config files, templates, etc.).
 * Distinct from sources: resources don't compile, they're packaged
 * verbatim.
 */
final class BspResourcesTool(val manager: BspManager) extends TypedTool[BspResourcesInput](
  name = ToolName("bsp_resources"),
  description =
    """List resource directories / files for the given build targets.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  examples = List(
    ToolExample(
      "list resources for every target",
      BspResourcesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspResourcesInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure("No targets.")
        else session.resources(targets).map { items =>
          if (items.isEmpty) "No resource items."
          else items.map { item =>
            val target = item.getTarget.getUri
            val res = Option(item.getResources).map(_.asScala.toList.mkString("\n      ")).getOrElse("")
            s"  $target\n      $res"
          }.mkString("\n")
        }
      }
    }
}
