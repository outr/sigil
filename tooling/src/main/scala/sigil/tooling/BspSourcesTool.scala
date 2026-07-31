package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{BspSourceItem, BspSourcesResult, BspTargetSources}

import scala.jdk.CollectionConverters.*

case class BspSourcesInput(projectRoot: String, targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List source roots / files for the given targets. Tells the agent
 * "what code does each sub-project actually own" — useful for
 * reasoning about build structure before edits.
 */
final class BspSourcesTool(val manager: BspManager) extends Tool with BspToolSupport {
  type Input = BspSourcesInput
  type Output = BspSourcesResult
  val io: ToolIO[BspSourcesInput, BspSourcesResult] = ToolIO.derived[BspSourcesInput, BspSourcesResult]

  override val name = ToolName("bsp_sources")
  override val description =
    """List source roots / files for the given build targets.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.
      |Returns each target's source items as `{uri, kind: "dir"|"file", generated}`.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set(
        "bsp",
        "sources",
        "source files",
        "list sources",
        "target sources",
        "scala",
        "sbt",
        "project",
        "files",
        "code",
        "examine",
        "inspect"
      ),
      toolchain = Some("bsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: BspSourcesInput, context: ToolContext): Task[BspSourcesResult] =
    withTargets[BspSourcesResult](
      input.projectRoot,
      context,
      input.targets,
      onError = msg => BspSourcesResult(input.projectRoot, Nil, error = Some(msg)),
      emptyResult = BspSourcesResult(input.projectRoot, Nil)
    ) { (session, targets) =>
      session.sources(targets).map { items =>
        BspSourcesResult(
          projectRoot = input.projectRoot,
          items = items.map { item =>
            BspTargetSources(
              target = item.getTarget.getUri,
              sources = Option(item.getSources).map(_.asScala.toList.map(BspSourceItem.fromBsp4j)).getOrElse(Nil)
            )
          }
        )
      }
    }
}
