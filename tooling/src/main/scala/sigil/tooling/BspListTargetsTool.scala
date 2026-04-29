package sigil.tooling

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class BspListTargetsInput(projectRoot: String) extends ToolInput derives RW

/**
 * List every build target the BSP server knows about — sub-projects,
 * test configurations, etc. The agent uses this to discover the
 * right target id before calling `bsp_compile` / `bsp_test` / etc.
 * with an explicit list.
 */
final class BspListTargetsTool(val manager: BspManager) extends TypedTool[BspListTargetsInput](
  name = ToolName("bsp_list_targets"),
  description =
    """List every build target advertised by the project's BSP server.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |Returns each target's URI, display name, language tags, and capabilities (canCompile / canTest / canRun).""".stripMargin,
  examples = List(
    ToolExample(
      "list targets in a project",
      BspListTargetsInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspListTargetsInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      session.workspaceBuildTargets.map { targets =>
        if (targets.isEmpty) "No build targets."
        else targets.map { t =>
          val caps = t.getCapabilities
          val flags = List(
            if (caps != null && caps.getCanCompile) Some("compile") else None,
            if (caps != null && caps.getCanTest)    Some("test")    else None,
            if (caps != null && caps.getCanRun)     Some("run")     else None,
            if (caps != null && caps.getCanDebug)   Some("debug")   else None
          ).flatten.mkString(",")
          val langs = Option(t.getLanguageIds).map(_.asScala.toList.mkString(",")).getOrElse("")
          val display = Option(t.getDisplayName).getOrElse("(unnamed)")
          s"  ${t.getId.getUri}\n      name: $display | langs: $langs | caps: $flags"
        }.mkString("\n")
      }
    }
}
