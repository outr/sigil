package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspResourcesResult, BspTargetResources}

import scala.jdk.CollectionConverters.*

case class BspResourcesInput(projectRoot: String,
                             targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List resource directories / files for the given targets — non-code
 * assets that ship in the build (config files, templates, etc.).
 * Distinct from sources: resources don't compile, they're packaged
 * verbatim.
 */
final class BspResourcesTool(val manager: BspManager) extends TypedOutputTool[BspResourcesInput, BspResourcesResult](
  name = ToolName("bsp_resources"),
  description =
    """List resource directories / files for the given build targets.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  keywords = Set("bsp", "resources", "target resources", "list resources"),
  examples = List(
    ToolExample(
      "list resources for every target",
      BspResourcesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  override protected def executeTyped(input: BspResourcesInput, context: TurnContext): Task[BspResourcesResult] =
    withSessionTyped[BspResourcesResult](
      input.projectRoot, context,
      onError = _ => BspResourcesResult(input.projectRoot, Nil)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure(BspResourcesResult(input.projectRoot, Nil))
        else session.resources(targets).map { items =>
          BspResourcesResult(
            projectRoot = input.projectRoot,
            items = items.map { item =>
              BspTargetResources(
                target    = item.getTarget.getUri,
                resources = Option(item.getResources).map(_.asScala.toList).getOrElse(Nil)
              )
            }
          )
        }
      }
    }
}
