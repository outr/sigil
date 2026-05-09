package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspTargetTestClasses, BspTestClassesResult}

import scala.jdk.CollectionConverters.*

case class BspScalaTestClassesInput(projectRoot: String,
                                    targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List discovered Scala test classes for each target — i.e. every
 * runnable test suite the build server has indexed. Useful for
 * "what test suites exist" before calling [[BspTestTool]] with a
 * `-z` filter or class name.
 *
 * Uses the legacy `buildTarget/scalaTestClasses` RPC (deprecated in
 * BSP in favor of `buildTarget/jvmTestEnvironment`, but still
 * shipped by sbt and Bloop).
 */
final class BspScalaTestClassesTool(val manager: BspManager) extends TypedOutputTool[BspScalaTestClassesInput, BspTestClassesResult](
  name = ToolName("bsp_scala_test_classes"),
  description =
    """List discovered Scala test classes for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.
      |Returns each target's test framework + class names.""".stripMargin,
  keywords = Set("bsp", "test classes", "tests", "scala", "find tests", "test suite"),
  examples = List(
    ToolExample(
      "list test classes",
      BspScalaTestClassesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspScalaTestClassesInput,
                                      context: TurnContext): Task[BspTestClassesResult] =
    withSessionTyped[BspTestClassesResult](
      input.projectRoot, context,
      onError = msg => throw new RuntimeException(msg)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure(BspTestClassesResult(input.projectRoot, Nil))
        else session.scalaTestClasses(targets).map { items =>
          BspTestClassesResult(
            projectRoot = input.projectRoot,
            items = items.map { item =>
              BspTargetTestClasses(
                target    = item.getTarget.getUri,
                framework = Option(item.getFramework).filter(_.nonEmpty),
                classes   = Option(item.getClasses).map(_.asScala.toList).getOrElse(Nil)
              )
            }
          )
        }
      }
    }
}
