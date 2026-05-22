package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{BspSourceItem, BspSourcesResult, BspTargetSources}

import scala.jdk.CollectionConverters.*

case class BspSourcesInput(projectRoot: String,
                           targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List source roots / files for the given targets. Tells the agent
 * "what code does each sub-project actually own" — useful for
 * reasoning about build structure before edits.
 */
final class BspSourcesTool(val manager: BspManager) extends Tool with BspToolSupport {
  type Input  = BspSourcesInput
  type Output = BspSourcesResult
  val inputRW  = summon[RW[BspSourcesInput]]
  val outputRW = summon[RW[BspSourcesResult]]

  val name = ToolName("bsp_sources")
  val description =
    """List source roots / files for the given build targets.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.
      |Returns each target's source items as `{uri, kind: "dir"|"file", generated}`.""".stripMargin
  override val keywords = Set(
    "bsp", "sources", "source files", "list sources", "target sources",
    "scala", "sbt", "project", "files", "code", "examine", "inspect"
  )

  override def paginate: Boolean = false

  override def executeOutput(input: BspSourcesInput, context: TurnContext): Task[BspSourcesResult] =
    withTargets[BspSourcesResult](
      input.projectRoot, context, input.targets,
      onError = _ => BspSourcesResult(input.projectRoot, Nil),
      emptyResult = BspSourcesResult(input.projectRoot, Nil)
    ) { (session, targets) =>
      session.sources(targets).map { items =>
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
