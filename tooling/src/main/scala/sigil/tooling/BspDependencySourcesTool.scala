package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspDependencySourcesResult, BspTargetDependencySources}

import scala.jdk.CollectionConverters.*

case class BspDependencySourcesInput(projectRoot: String,
                                     targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List the source jars for each target's library dependencies.
 * The agent uses this to grep into third-party code when a hover
 * doesn't answer the question — equivalent to "navigate into
 * source jar" in an IDE.
 */
final class BspDependencySourcesTool(val manager: BspManager) extends TypedOutputTool[BspDependencySourcesInput, BspDependencySourcesResult](
  name = ToolName("bsp_dependency_sources"),
  description =
    """List the source jars for each target's library dependencies.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  keywords = Set("bsp", "dependency sources", "library sources", "deps source", "external sources")
) with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: BspDependencySourcesInput,
                                      context: TurnContext): Task[BspDependencySourcesResult] =
    withSessionTyped[BspDependencySourcesResult](
      input.projectRoot, context,
      onError = _ => BspDependencySourcesResult(input.projectRoot, Nil)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure(BspDependencySourcesResult(input.projectRoot, Nil))
        else session.dependencySources(targets).map { items =>
          BspDependencySourcesResult(
            projectRoot = input.projectRoot,
            items = items.map { item =>
              BspTargetDependencySources(
                target  = item.getTarget.getUri,
                sources = Option(item.getSources).map(_.asScala.toList).getOrElse(Nil)
              )
            }
          )
        }
      }
    }
}
