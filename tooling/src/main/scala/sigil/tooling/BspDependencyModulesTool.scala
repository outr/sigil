package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspDependencyModule, BspDependencyModulesResult, BspTargetDependencyModules}

import scala.jdk.CollectionConverters.*

case class BspDependencyModulesInput(projectRoot: String,
                                     targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List each target's library dependencies as Maven coordinates
 * (or whatever the build server's module resolver returns —
 * `org:artifact:version` for Scala/sbt).
 *
 * Higher-level than `bsp_dependency_sources`: that returns jar
 * paths; this returns the coordinates the build references. Useful
 * for "what version of X does this project pull in?"
 */
final class BspDependencyModulesTool(val manager: BspManager) extends TypedOutputTool[BspDependencyModulesInput, BspDependencyModulesResult](
  name = ToolName("bsp_dependency_modules"),
  description =
    """List each target's library dependencies as module coordinates (groupId:artifactId, version).
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  examples = List(
    ToolExample(
      "list dependency modules",
      BspDependencyModulesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspDependencyModulesInput,
                                      context: TurnContext): Task[BspDependencyModulesResult] =
    withSessionTyped[BspDependencyModulesResult](
      input.projectRoot, context,
      onError = msg => throw new RuntimeException(msg)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure(BspDependencyModulesResult(input.projectRoot, Nil))
        else session.dependencyModules(targets).map { items =>
          BspDependencyModulesResult(
            projectRoot = input.projectRoot,
            items = items.map { item =>
              BspTargetDependencyModules(
                target  = item.getTarget.getUri,
                modules = Option(item.getModules).map(_.asScala.toList.map { m =>
                  BspDependencyModule(name = m.getName, version = m.getVersion)
                }).getOrElse(Nil)
              )
            }
          )
        }
      }
    }
}
