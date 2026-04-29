package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class BspDependencyModulesInput(projectRoot: String,
                                     targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List each target's library dependencies as Maven coordinates
 * (or whatever the build server's module resolver returns —
 * `org:artifact:version` for Scala/sbt).
 *
 * Higher-level than `bsp_dependency_sources`: that returns jar
 * paths; this returns the coordinates the build references. Useful
 * for "what version of X does this project pull in?"
 */
final class BspDependencyModulesTool(val manager: BspManager) extends TypedTool[BspDependencyModulesInput](
  name = ToolName("bsp_dependency_modules"),
  description =
    """List each target's library dependencies as module coordinates (groupId:artifactId:version).
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  examples = List(
    ToolExample(
      "list dependency modules",
      BspDependencyModulesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspDependencyModulesInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure("No targets.")
        else session.dependencyModules(targets).map { items =>
          if (items.isEmpty) "No dependency module items."
          else items.map { item =>
            val target = item.getTarget.getUri
            val mods = Option(item.getModules).map(_.asScala.toList.map { m =>
              s"      ${m.getName}:${m.getVersion}"
            }.mkString("\n")).getOrElse("")
            s"  $target\n$mods"
          }.mkString("\n")
        }
      }
    }
}
