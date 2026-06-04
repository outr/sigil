package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{BspMainClassEntry, BspMainClassesResult, BspTargetMainClasses}

import scala.jdk.CollectionConverters.*

case class BspScalaMainClassesInput(projectRoot: String,
                                    targets: List[String] = Nil)
  extends ToolInput derives RW

/**
 * List discovered Scala `main` classes for each target — every
 * runnable entrypoint the build server has indexed. Useful before
 * calling [[BspRunTool]] when the agent doesn't know which class
 * to run.
 */
final class BspScalaMainClassesTool(val manager: BspManager) extends Tool with BspToolSupport {
  type Input = BspScalaMainClassesInput
  type Output = BspMainClassesResult
  val inputRW = summon[RW[BspScalaMainClassesInput]]
  val outputRW = summon[RW[BspMainClassesResult]]

  val name = ToolName("bsp_scala_main_classes")
  val description =
    """List discovered Scala main classes for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin
  override val keywords = Set("bsp", "main classes", "main", "entry points", "scala", "runnable")

  override def executeOutput(input: BspScalaMainClassesInput,
                             context: ToolContext): Task[BspMainClassesResult] =
    withTargets[BspMainClassesResult](
      input.projectRoot,
      context,
      input.targets,
      onError = _ => BspMainClassesResult(input.projectRoot, Nil),
      emptyResult = BspMainClassesResult(input.projectRoot, Nil)
    ) { (session, targets) =>
      session.scalaMainClasses(targets).map { items =>
        BspMainClassesResult(
          projectRoot = input.projectRoot,
          items = items.map { item =>
            BspTargetMainClasses(
              target = item.getTarget.getUri,
              classes = Option(item.getClasses).map(_.asScala.toList.map { c =>
                BspMainClassEntry(
                  className = c.getClassName,
                  arguments = Option(c.getArguments).map(_.asScala.toList).getOrElse(Nil)
                )
              }).getOrElse(Nil)
            )
          }
        )
      }
    }
}
