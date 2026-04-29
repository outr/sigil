package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class BspScalacOptionsInput(projectRoot: String,
                                 targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List the scalac options + classpath for each target. The agent
 * uses this to verify language feature flags (`-deprecation`,
 * `-Xfatal-warnings`, etc.) and inspect the classpath when chasing
 * resolution issues.
 */
final class BspScalacOptionsTool(val manager: BspManager) extends TypedTool[BspScalacOptionsInput](
  name = ToolName("bsp_scalac_options"),
  description =
    """List scalac options + classpath for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  examples = List(
    ToolExample(
      "list scalac options",
      BspScalacOptionsInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspScalacOptionsInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure("No targets.")
        else session.scalacOptions(targets).map { items =>
          if (items.isEmpty) "No scalac options."
          else items.map { item =>
            val target = item.getTarget.getUri
            val opts = Option(item.getOptions).map(_.asScala.toList.mkString(" ")).getOrElse("")
            val classpath = Option(item.getClasspath).map(_.asScala.toList.size).getOrElse(0)
            val classDir = Option(item.getClassDirectory).getOrElse("")
            s"  $target\n      options: $opts\n      classDirectory: $classDir\n      classpath entries: $classpath"
          }.mkString("\n")
        }
      }
    }
}
