package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{BspDependencySourcesResult, BspTargetDependencySources}

import scala.jdk.CollectionConverters.*

case class BspDependencySourcesInput(projectRoot: String, targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List the source jars for each target's library dependencies.
 * The agent uses this to grep into third-party code when a hover
 * doesn't answer the question — equivalent to "navigate into
 * source jar" in an IDE.
 */
final class BspDependencySourcesTool(val manager: BspManager) extends Tool with BspToolSupport {
  type Input = BspDependencySourcesInput
  type Output = BspDependencySourcesResult
  val io: ToolIO[BspDependencySourcesInput, BspDependencySourcesResult] =
    ToolIO.derived[BspDependencySourcesInput, BspDependencySourcesResult]

  override val name = ToolName("bsp_dependency_sources")
  override val description =
    """List the source jars for each target's library dependencies.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("bsp", "dependency sources", "library sources", "deps source", "external sources"),
      toolchain = Some("bsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: BspDependencySourcesInput, context: ToolContext): Task[BspDependencySourcesResult] =
    withTargets[BspDependencySourcesResult](
      input.projectRoot,
      context,
      input.targets,
      onError = msg => BspDependencySourcesResult(input.projectRoot, Nil, error = Some(msg)),
      emptyResult = BspDependencySourcesResult(input.projectRoot, Nil)
    ) { (session, targets) =>
      session.dependencySources(targets).map { items =>
        BspDependencySourcesResult(
          projectRoot = input.projectRoot,
          items = items.map { item =>
            BspTargetDependencySources(
              target = item.getTarget.getUri,
              sources = Option(item.getSources).map(_.asScala.toList).getOrElse(Nil)
            )
          }
        )
      }
    }
}
