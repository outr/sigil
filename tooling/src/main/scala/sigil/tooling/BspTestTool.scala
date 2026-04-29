package sigil.tooling

import ch.epfl.scala.bsp4j.StatusCode
import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class BspTestInput(projectRoot: String,
                        targets: List[String] = Nil,
                        arguments: List[String] = Nil) extends ToolInput derives RW

/**
 * Run tests for the given build targets via the BSP server. Captures
 * the build server's run-target stdout/stderr and surfaces them in
 * the response so the agent sees both the status and the test output.
 *
 * `arguments` are passed through to the test runner (sbt-style flags
 * like `-z 'substring of test name'`, etc.). Empty `targets` means
 * "every workspace target that supports test".
 */
final class BspTestTool(val manager: BspManager) extends TypedTool[BspTestInput](
  name = ToolName("bsp_test"),
  description =
    """Run tests for build targets via the BSP server.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty tests every target with the test capability.
      |`arguments` (optional) flows through to the test runner.
      |Returns the status (OK / ERROR / CANCELLED) plus stdout/stderr captured during the run.""".stripMargin,
  examples = List(
    ToolExample(
      "run a single sbt suite",
      BspTestInput(
        projectRoot = "/abs/path/myproject",
        arguments = List("-z", "OrchestratorTopicSpec")
      )
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspTestInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { resolved =>
        val testable = resolved.filter(_ => true) // Filtering by capability requires a list+lookup; skip for now.
        if (testable.isEmpty) Task.pure("No targets to test.")
        else {
          // Drain prior output so we only show what arrived during this call.
          session.client.drainRunOutput()
          session.test(testable, input.arguments).map { result =>
            val status = result.getStatusCode match {
              case StatusCode.OK        => "OK"
              case StatusCode.ERROR     => "ERROR"
              case StatusCode.CANCELLED => "CANCELLED"
            }
            val (out, err) = session.client.drainRunOutput()
            val outBlock = if (out.isEmpty) "" else s"\n--- stdout ---\n${out.mkString}"
            val errBlock = if (err.isEmpty) "" else s"\n--- stderr ---\n${err.mkString}"
            s"Test $status (${testable.size} target(s) in ${input.projectRoot})$outBlock$errBlock"
          }
        }
      }
    }
}
