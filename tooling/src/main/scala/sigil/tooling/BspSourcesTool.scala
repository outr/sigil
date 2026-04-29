package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class BspSourcesInput(projectRoot: String,
                           targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List source roots / files for the given targets. Tells the agent
 * "what code does each sub-project actually own" — useful for
 * reasoning about build structure before edits.
 */
final class BspSourcesTool(val manager: BspManager) extends TypedTool[BspSourcesInput](
  name = ToolName("bsp_sources"),
  description =
    """List source roots / files for the given build targets.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  examples = List(
    ToolExample(
      "list sources for every target",
      BspSourcesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspSourcesInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure("No targets.")
        else session.sources(targets).map { items =>
          if (items.isEmpty) "No source items."
          else items.map { item =>
            val target = item.getTarget.getUri
            val sources = Option(item.getSources).map(_.asScala.toList.map { s =>
              val kind = if (s.getKind == ch.epfl.scala.bsp4j.SourceItemKind.DIRECTORY) "dir" else "file"
              s"      [$kind] ${s.getUri}"
            }.mkString("\n")).getOrElse("")
            s"  $target\n$sources"
          }.mkString("\n")
        }
      }
    }
}
