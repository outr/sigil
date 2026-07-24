package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{BspScalacOptionsResult, BspTargetScalacOptions}

import scala.jdk.CollectionConverters.*

case class BspScalacOptionsInput(projectRoot: String,
                                 targets: List[String] = Nil)
  extends ToolInput derives RW

/**
 * List the scalac options + classpath for each target. The agent
 * uses this to verify language feature flags (`-deprecation`,
 * `-Xfatal-warnings`, etc.) and inspect the classpath when chasing
 * resolution issues.
 */
final class BspScalacOptionsTool(val manager: BspManager) extends Tool with BspToolSupport {
  type Input = BspScalacOptionsInput
  type Output = BspScalacOptionsResult
  val inputRW = summon[RW[BspScalacOptionsInput]]
  val outputRW = summon[RW[BspScalacOptionsResult]]

  val name = ToolName("bsp_scalac_options")
  val description =
    """List scalac options + classpath for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin
  override val keywords = Set("bsp", "scalac", "scalac options", "compiler options", "compile flags", "scala")

  override def executeOutput(input: BspScalacOptionsInput,
                             context: ToolContext): Task[BspScalacOptionsResult] =
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
