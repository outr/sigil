package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
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
final class BspDependencySourcesTool(val manager: BspManager) extends Tool
  with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  type Input  = BspDependencySourcesInput
  type Output = BspDependencySourcesResult
  val inputRW  = summon[RW[BspDependencySourcesInput]]
  val outputRW = summon[RW[BspDependencySourcesResult]]

  val name = ToolName("bsp_dependency_sources")
  val description =
    """List the source jars for each target's library dependencies.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin
  override val keywords = Set("bsp", "dependency sources", "library sources", "deps source", "external sources")


  override def executeOutput(input: BspDependencySourcesInput,
                             context: ToolContext): Task[BspDependencySourcesResult] =
    withTargets[BspDependencySourcesResult](
      input.projectRoot, context, input.targets,
      onError = _ => BspDependencySourcesResult(input.projectRoot, Nil),
      emptyResult = BspDependencySourcesResult(input.projectRoot, Nil)
    ) { (session, targets) =>
      session.dependencySources(targets).map { items =>
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
