package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{BspMainClassEntry, BspMainClassesResult, BspTargetMainClasses}

import scala.jdk.CollectionConverters.*

case class BspScalaMainClassesInput(projectRoot: String, targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List discovered Scala `main` classes for each target — every
 * runnable entrypoint the build server has indexed. Useful before
 * calling [[BspRunTool]] when the agent doesn't know which class
 * to run.
 */
final class BspScalaMainClassesTool(val manager: BspManager) extends Tool with BspToolSupport {
  type Input = BspScalaMainClassesInput
  type Output = BspMainClassesResult
  val io: ToolIO[BspScalaMainClassesInput, BspMainClassesResult] = ToolIO.derived[BspScalaMainClassesInput, BspMainClassesResult]

  override val name = ToolName("bsp_scala_main_classes")
  override val description =
    """List discovered Scala main classes for each target.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("bsp", "main classes", "main", "entry points", "scala", "runnable"),
      toolchain = Some("bsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: BspScalaMainClassesInput, context: ToolContext): Task[BspMainClassesResult] =
    withTargets[BspMainClassesResult](
      input.projectRoot,
      context,
      input.targets,
      onError = msg => BspMainClassesResult(input.projectRoot, Nil, error = Some(msg)),
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
