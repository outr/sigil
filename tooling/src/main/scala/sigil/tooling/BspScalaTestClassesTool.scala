package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

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
final class BspScalaTestClassesTool(val manager: BspManager) extends TypedTool[BspScalaTestClassesInput](
  name = ToolName("bsp_scala_test_classes"),
  description =
    """List discovered Scala test classes for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.
      |Returns each target's test framework + class names.""".stripMargin,
  examples = List(
    ToolExample(
      "list test classes",
      BspScalaTestClassesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspScalaTestClassesInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure("No targets.")
        else session.scalaTestClasses(targets).map { items =>
          if (items.isEmpty) "No test classes discovered."
          else items.map { item =>
            val target = item.getTarget.getUri
            val classes = Option(item.getClasses).map(_.asScala.toList.mkString("\n      ")).getOrElse("")
            val framework = Option(item.getFramework).getOrElse("")
            s"  $target  [framework: $framework]\n      $classes"
          }.mkString("\n")
        }
      }
    }
}
