package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspMainClassEntry, BspMainClassesResult, BspTargetMainClasses}

import scala.jdk.CollectionConverters.*

case class BspScalaMainClassesInput(projectRoot: String,
                                    targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List discovered Scala `main` classes for each target — every
 * runnable entrypoint the build server has indexed. Useful before
 * calling [[BspRunTool]] when the agent doesn't know which class
 * to run.
 */
final class BspScalaMainClassesTool(val manager: BspManager) extends TypedOutputTool[BspScalaMainClassesInput, BspMainClassesResult](
  name = ToolName("bsp_scala_main_classes"),
  description =
    """List discovered Scala main classes for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  keywords = Set("bsp", "main classes", "main", "entry points", "scala", "runnable"),
  examples = List(
    ToolExample(
      "list main classes",
      BspScalaMainClassesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspScalaMainClassesInput,
                                      context: TurnContext): Task[BspMainClassesResult] =
    withSessionTyped[BspMainClassesResult](
      input.projectRoot, context,
      onError = _ => BspMainClassesResult(input.projectRoot, Nil)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure(BspMainClassesResult(input.projectRoot, Nil))
        else session.scalaMainClasses(targets).map { items =>
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
}
