package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspBuildTarget, BspListTargetsResult}

case class BspListTargetsInput(projectRoot: String) extends ToolInput derives RW

/**
 * List every build target the BSP server knows about — sub-projects,
 * test configurations, etc. The agent uses this to discover the
 * right target id before calling `bsp_compile` / `bsp_test` / etc.
 * with an explicit list.
 */
final class BspListTargetsTool(val manager: BspManager) extends TypedOutputTool[BspListTargetsInput, BspListTargetsResult](
  name = ToolName("bsp_list_targets"),
  description =
    """List every build target advertised by the project's BSP server.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |Returns each target's URI, display name, language tags, and capabilities (canCompile / canTest / canRun / canDebug).""".stripMargin,
  keywords = Set(
    "bsp", "targets", "list targets", "build targets", "modules",
    "examine", "inspect", "scala", "sbt", "project", "build"
  ),
  examples = List(
    ToolExample(
      "list targets in a project",
      BspListTargetsInput(projectRoot = "/abs/path/myproject")
    )
  )
) with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  override protected def executeTyped(input: BspListTargetsInput, context: TurnContext): Task[BspListTargetsResult] =
    withSessionTyped[BspListTargetsResult](
      input.projectRoot, context,
      onError = _ => BspListTargetsResult(input.projectRoot, Nil)
    ) { session =>
      session.workspaceBuildTargets.map { targets =>
        BspListTargetsResult(
          projectRoot = input.projectRoot,
          targets     = targets.map(BspBuildTarget.fromBsp4j)
        )
      }
    }
}
