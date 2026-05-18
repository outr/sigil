package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspScalacOptionsResult, BspTargetScalacOptions}

import scala.jdk.CollectionConverters.*

case class BspScalacOptionsInput(projectRoot: String,
                                 targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List the scalac options + classpath for each target. The agent
 * uses this to verify language feature flags (`-deprecation`,
 * `-Xfatal-warnings`, etc.) and inspect the classpath when chasing
 * resolution issues.
 */
final class BspScalacOptionsTool(val manager: BspManager) extends TypedOutputTool[BspScalacOptionsInput, BspScalacOptionsResult](
  name = ToolName("bsp_scalac_options"),
  description =
    """List scalac options + classpath for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  keywords = Set("bsp", "scalac", "scalac options", "compiler options", "compile flags", "scala")
) with BspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: BspScalacOptionsInput,
                                      context: TurnContext): Task[BspScalacOptionsResult] =
    withSessionTyped[BspScalacOptionsResult](
      input.projectRoot, context,
      onError = _ => BspScalacOptionsResult(input.projectRoot, Nil)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure(BspScalacOptionsResult(input.projectRoot, Nil))
        else session.scalacOptions(targets).map { items =>
          BspScalacOptionsResult(
            projectRoot = input.projectRoot,
            items = items.map { item =>
              BspTargetScalacOptions(
                target         = item.getTarget.getUri,
                options        = Option(item.getOptions).map(_.asScala.toList).getOrElse(Nil),
                classDirectory = Option(item.getClassDirectory).filter(_.nonEmpty),
                classpath      = Option(item.getClasspath).map(_.asScala.toList).getOrElse(Nil)
              )
            }
          )
        }
      }
    }
}
