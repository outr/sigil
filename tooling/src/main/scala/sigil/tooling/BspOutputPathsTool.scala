package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspOutputPathItem, BspOutputPathsResult, BspTargetOutputPaths}

import scala.jdk.CollectionConverters.*

case class BspOutputPathsInput(projectRoot: String,
                               targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List the build output directories (compiled classes, packaged
 * jars) for each target. Useful for the agent to locate compiled
 * artifacts directly when a downstream tool needs the classpath
 * or jar output.
 */
final class BspOutputPathsTool(val manager: BspManager) extends TypedOutputTool[BspOutputPathsInput, BspOutputPathsResult](
  name = ToolName("bsp_output_paths"),
  description =
    """List build output directories / jars for the given targets.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  keywords = Set("bsp", "output", "output paths", "classpath", "build output")
) with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: BspOutputPathsInput,
                                      context: TurnContext): Task[BspOutputPathsResult] =
    withSessionTyped[BspOutputPathsResult](
      input.projectRoot, context,
      onError = _ => BspOutputPathsResult(input.projectRoot, Nil)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure(BspOutputPathsResult(input.projectRoot, Nil))
        else session.outputPaths(targets).map { items =>
          BspOutputPathsResult(
            projectRoot = input.projectRoot,
            items = items.map { item =>
              BspTargetOutputPaths(
                target = item.getTarget.getUri,
                paths = Option(item.getOutputPaths).map(_.asScala.toList.map(BspOutputPathItem.fromBsp4j)).getOrElse(Nil)
              )
            }
          )
        }
      }
    }
}
