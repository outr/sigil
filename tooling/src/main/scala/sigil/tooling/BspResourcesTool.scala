package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Tool, ToolInput, ToolName, ToolProfile, ToolSpec}
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
final class BspResourcesTool(val manager: BspManager) extends Tool
  with BspToolSupport {
  type Input  = BspResourcesInput
  type Output = BspResourcesResult
  val inputRW  = summon[RW[BspResourcesInput]]
  val outputRW = summon[RW[BspResourcesResult]]

  override val name = ToolName("bsp_resources")
  override val description =
    """List resource directories / files for the given build targets.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("bsp", "resources", "target resources", "list resources"),
      toolchain = Some("bsp")
    )
  )

  override def executeOutput(input: BspResourcesInput, context: ToolContext): Task[BspResourcesResult] =
    withTargets[BspResourcesResult](
      input.projectRoot, context, input.targets,
      onError = msg => BspResourcesResult(input.projectRoot, Nil, error = Some(msg)),
      emptyResult = BspResourcesResult(input.projectRoot, Nil)
    ) { (session, targets) =>
      session.resources(targets).map { items =>
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
