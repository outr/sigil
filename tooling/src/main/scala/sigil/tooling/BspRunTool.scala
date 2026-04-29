package sigil.tooling

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, StatusCode}
import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class BspRunInput(projectRoot: String,
                       target: String,
                       arguments: List[String] = Nil) extends ToolInput derives RW

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
final class BspRunTool(val manager: BspManager) extends TypedTool[BspRunInput](
  name = ToolName("bsp_run"),
  description =
    """Run a single build target via the BSP server (typically a `Main` class).
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`target` is the target URI to run.
      |`arguments` (optional) flows through to the running program.
      |Returns status plus captured stdout/stderr.""".stripMargin,
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
) with BspToolSupport {
  override protected def executeTyped(input: BspRunInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      session.client.drainRunOutput()
      session.run(new BuildTargetIdentifier(input.target), input.arguments).map { result =>
        val status = result.getStatusCode match {
          case StatusCode.OK        => "OK"
          case StatusCode.ERROR     => "ERROR"
          case StatusCode.CANCELLED => "CANCELLED"
        }
        val (out, err) = session.client.drainRunOutput()
        val outBlock = if (out.isEmpty) "" else s"\n--- stdout ---\n${out.mkString}"
        val errBlock = if (err.isEmpty) "" else s"\n--- stderr ---\n${err.mkString}"
        s"Run $status (${input.target} in ${input.projectRoot})$outBlock$errBlock"
      }
    }
}
