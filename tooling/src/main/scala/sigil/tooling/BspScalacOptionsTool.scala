package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{BspScalacOptionsResult, BspTargetScalacOptions}

import scala.jdk.CollectionConverters.*

case class BspScalacOptionsInput(projectRoot: String, targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List the scalac options + classpath for each target. The agent
 * uses this to verify language feature flags (`-deprecation`,
 * `-Xfatal-warnings`, etc.) and inspect the classpath when chasing
 * resolution issues.
 */
final class BspScalacOptionsTool(val manager: BspManager) extends Tool with BspToolSupport {
  type Input = BspScalacOptionsInput
  type Output = BspScalacOptionsResult
  val io: ToolIO[BspScalacOptionsInput, BspScalacOptionsResult] = ToolIO.derived[BspScalacOptionsInput, BspScalacOptionsResult]

  override val name = ToolName("bsp_scalac_options")
  override val description =
    """List scalac options + classpath for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("bsp", "scalac", "scalac options", "compiler options", "compile flags", "scala"),
      toolchain = Some("bsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: BspScalacOptionsInput, context: ToolContext): Task[BspScalacOptionsResult] =
    withTargets[BspScalacOptionsResult](
      input.projectRoot,
      context,
      input.targets,
      onError = msg => BspScalacOptionsResult(input.projectRoot, Nil, error = Some(msg)),
      emptyResult = BspScalacOptionsResult(input.projectRoot, Nil)
    ) { (session, targets) =>
      session.scalacOptions(targets).map { items =>
        BspScalacOptionsResult(
          projectRoot = input.projectRoot,
          items = items.map { item =>
            BspTargetScalacOptions(
              target = item.getTarget.getUri,
              options = Option(item.getOptions).map(_.asScala.toList).getOrElse(Nil),
              classDirectory = Option(item.getClassDirectory).filter(_.nonEmpty),
              classpath = Option(item.getClasspath).map(_.asScala.toList).getOrElse(Nil)
            )
          }
        )
      }
    }
}
