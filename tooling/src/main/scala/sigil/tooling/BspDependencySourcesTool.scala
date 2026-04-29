package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class BspDependencySourcesInput(projectRoot: String,
                                     targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List the source jars for each target's library dependencies.
 * The agent uses this to grep into third-party code when a hover
 * doesn't answer the question — equivalent to "navigate into
 * source jar" in an IDE.
 */
final class BspDependencySourcesTool(val manager: BspManager) extends TypedTool[BspDependencySourcesInput](
  name = ToolName("bsp_dependency_sources"),
  description =
    """List the source jars for each target's library dependencies.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  examples = List(
    ToolExample(
      "list dependency sources",
      BspDependencySourcesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspDependencySourcesInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure("No targets.")
        else session.dependencySources(targets).map { items =>
          if (items.isEmpty) "No dependency source items."
          else items.map { item =>
            val target = item.getTarget.getUri
            val sources = Option(item.getSources).map(_.asScala.toList.mkString("\n      ")).getOrElse("")
            s"  $target\n      $sources"
          }.mkString("\n")
        }
      }
    }
}
