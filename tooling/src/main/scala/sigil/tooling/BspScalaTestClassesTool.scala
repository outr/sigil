package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Tool, ToolInput, ToolName, ToolProfile, ToolSpec}
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
final class BspScalaTestClassesTool(val manager: BspManager) extends Tool with BspToolSupport {
  type Input  = BspScalaTestClassesInput
  type Output = BspTestClassesResult
  val inputRW  = summon[RW[BspScalaTestClassesInput]]
  val outputRW = summon[RW[BspTestClassesResult]]

  override val name = ToolName("bsp_scala_test_classes")
  override val description =
    """List discovered Scala test classes for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.
      |Returns each target's test framework + class names.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("bsp", "test classes", "tests", "scala", "find tests", "test suite"),
      toolchain = Some("bsp")
    )
  )

  override def executeOutput(input: BspScalaTestClassesInput,
                             context: ToolContext): Task[BspTestClassesResult] =
    withTargets[BspTestClassesResult](
      input.projectRoot, context, input.targets,
      onError = msg => BspTestClassesResult(input.projectRoot, Nil, error = Some(msg)),
      emptyResult = BspTestClassesResult(input.projectRoot, Nil)
    ) { (session, targets) =>
      session.scalaTestClasses(targets).map { items =>
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
