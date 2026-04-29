package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class BspOutputPathsInput(projectRoot: String,
                               targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List the build output directories (compiled classes, packaged
 * jars) for each target. Useful for the agent to locate compiled
 * artifacts directly when a downstream tool needs the classpath
 * or jar output.
 */
final class BspOutputPathsTool(val manager: BspManager) extends TypedTool[BspOutputPathsInput](
  name = ToolName("bsp_output_paths"),
  description =
    """List build output directories / jars for the given targets.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  examples = List(
    ToolExample(
      "list output paths",
      BspOutputPathsInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspOutputPathsInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure("No targets.")
        else session.outputPaths(targets).map { items =>
          if (items.isEmpty) "No output items."
          else items.map { item =>
            val target = item.getTarget.getUri
            val paths = Option(item.getOutputPaths).map(_.asScala.toList.map { p =>
              val kind = if (p.getKind == ch.epfl.scala.bsp4j.OutputPathItemKind.DIRECTORY) "dir" else "file"
              s"      [$kind] ${p.getUri}"
            }.mkString("\n")).getOrElse("")
            s"  $target\n$paths"
          }.mkString("\n")
        }
      }
    }
}
