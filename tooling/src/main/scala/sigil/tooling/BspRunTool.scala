package sigil.tooling

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, StatusCode}
import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.BspExecResult

case class BspRunInput(projectRoot: String,
                       target: String,
                       arguments: List[String] = Nil)
  extends ToolInput derives RW

/**
 * Run a build target via the BSP server. `target` is the single
 * target URI to run (`bsp_list_targets` returns the available
 * options); the agent picks one. Captures the program's stdout and
 * stderr and surfaces them alongside the exit status.
 *
 * Useful for quick "let me run this and see" loops where the agent
 * is debugging a small program. For long-running services or
 * interactive programs, prefer running outside Sigil.
 */
final class BspRunTool(val manager: BspManager)
  extends TypedOutputTool[BspRunInput, BspExecResult](
    name = ToolName("bsp_run"),
    description =
      """Run a single build target via the BSP server (typically a `Main` class).
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`target` is the target URI to run.
      |`arguments` (optional) flows through to the running program.
      |Returns `{status, targetCount: 1, stdout, stderr}` where status is `OK` / `ERROR` / `CANCELLED`.""".stripMargin,
    keywords = Set("bsp", "run", "execute", "main", "launch", "start"),
    examples = List(
      ToolExample(
        "run a main class",
        BspRunInput(
          projectRoot = "/abs/path/myproject",
          target = "file:///abs/path/myproject/?id=core",
          arguments = List("--mode", "demo")
        )
      )
    )
  )
  with sigil.tool.DestructiveExternalTool
  with BspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: BspRunInput, context: TurnContext): Task[BspExecResult] =
    withSessionTyped[BspExecResult](
      input.projectRoot,
      context,
      onError = msg => BspExecResult(input.projectRoot, "ERROR", 0, "", msg)
    ) { session =>
      session.client.drainRunOutput()
      session.run(new BuildTargetIdentifier(input.target), input.arguments).map { result =>
        val status = result.getStatusCode match {
          case StatusCode.OK => "OK"
          case StatusCode.ERROR => "ERROR"
          case StatusCode.CANCELLED => "CANCELLED"
        }
        val (out, err) = session.client.drainRunOutput()
        BspExecResult(
          projectRoot = input.projectRoot,
          status = status,
          targetCount = 1,
          stdout = out.mkString,
          stderr = err.mkString
        )
      }
    }
}
