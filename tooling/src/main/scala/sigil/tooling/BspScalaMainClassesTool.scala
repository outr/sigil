package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class BspScalaMainClassesInput(projectRoot: String,
                                    targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List discovered Scala `main` classes for each target — every
 * runnable entrypoint the build server has indexed. Useful before
 * calling [[BspRunTool]] when the agent doesn't know which class
 * to run.
 */
final class BspScalaMainClassesTool(val manager: BspManager) extends TypedTool[BspScalaMainClassesInput](
  name = ToolName("bsp_scala_main_classes"),
  description =
    """List discovered Scala main classes for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  examples = List(
    ToolExample(
      "list main classes",
      BspScalaMainClassesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspScalaMainClassesInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure("No targets.")
        else session.scalaMainClasses(targets).map { items =>
          if (items.isEmpty) "No main classes discovered."
          else items.map { item =>
            val target = item.getTarget.getUri
            val classes = Option(item.getClasses).map(_.asScala.toList.map { c =>
              val args = Option(c.getArguments).map(_.asScala.toList.mkString(" ")).getOrElse("")
              s"      ${c.getClassName}${if (args.nonEmpty) s"  ($args)" else ""}"
            }.mkString("\n")).getOrElse("")
            s"  $target\n$classes"
          }.mkString("\n")
        }
      }
    }
}
