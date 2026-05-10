package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspSourceItem, BspSourcesResult, BspTargetSources}

import scala.jdk.CollectionConverters.*

case class BspSourcesInput(projectRoot: String,
                           targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List source roots / files for the given targets. Tells the agent
 * "what code does each sub-project actually own" — useful for
 * reasoning about build structure before edits.
 */
final class BspSourcesTool(val manager: BspManager) extends TypedOutputTool[BspSourcesInput, BspSourcesResult](
  name = ToolName("bsp_sources"),
  description =
    """List source roots / files for the given build targets.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.
      |Returns each target's source items as `{uri, kind: "dir"|"file", generated}`.""".stripMargin,
  keywords = Set(
    "bsp", "sources", "source files", "list sources", "target sources",
    "scala", "sbt", "project", "files", "code", "examine", "inspect"
  ),
  examples = List(
    ToolExample(
      "list sources for every target",
      BspSourcesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspSourcesInput, context: TurnContext): Task[BspSourcesResult] =
    withSessionTyped[BspSourcesResult](
      input.projectRoot, context,
      onError = _ => BspSourcesResult(input.projectRoot, Nil)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure(BspSourcesResult(input.projectRoot, Nil))
        else session.sources(targets).map { items =>
          BspSourcesResult(
            projectRoot = input.projectRoot,
            items = items.map { item =>
              BspTargetSources(
                target  = item.getTarget.getUri,
                sources = Option(item.getSources).map(_.asScala.toList.map(BspSourceItem.fromBsp4j)).getOrElse(Nil)
              )
            }
          )
        }
      }
    }
}
