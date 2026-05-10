package sigil.tooling

import ch.epfl.scala.bsp4j.StatusCode
import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.BspExecResult

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
final class BspTestTool(val manager: BspManager) extends TypedOutputTool[BspTestInput, BspExecResult](
  name = ToolName("bsp_test"),
  description =
    """Run tests for build targets via the BSP server.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty tests every target with the test capability.
      |`arguments` (optional) flows through to the test runner.
      |Returns `{status, targetCount, stdout, stderr}` where status is `OK` / `ERROR` / `CANCELLED` / `NO_TARGETS`.""".stripMargin,
  keywords = Set(
    "bsp", "test", "run tests", "unit test", "execute tests", "verify",
    "scala", "sbt", "project", "targets", "validate"
  ),
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
  override protected def executeTyped(input: BspTestInput, context: TurnContext): Task[BspExecResult] =
    withSessionTyped[BspExecResult](
      input.projectRoot, context,
      onError = msg => BspExecResult(input.projectRoot, "ERROR", 0, "", msg)
    ) { session =>
      // Filter to test-capable targets so empty input doesn't expand
      // to "every target including the meta-build" — sbt-bsp's
      // bspBuildTargetTest throws NoSuchElementException when handed
      // a non-test target.
      session.workspaceBuildTargets.flatMap { allTargets =>
        val testable = allTargets.filter(_.getCapabilities.getCanTest)
        val resolved = if (input.targets.nonEmpty) {
          val requested = input.targets.toSet
          testable.filter(t => requested.contains(t.getId.getUri)).map(_.getId)
        } else testable.map(_.getId)
        if (resolved.isEmpty) Task.pure(BspExecResult(input.projectRoot, "NO_TARGETS", 0, "", ""))
        else {
          session.client.drainRunOutput()
          session.test(resolved, input.arguments).map { result =>
            val status = result.getStatusCode match {
              case StatusCode.OK        => "OK"
              case StatusCode.ERROR     => "ERROR"
              case StatusCode.CANCELLED => "CANCELLED"
            }
            val (out, err) = session.client.drainRunOutput()
            BspExecResult(
              projectRoot = input.projectRoot,
              status      = status,
              targetCount = resolved.size,
              stdout      = out.mkString,
              stderr      = err.mkString
            )
          }.handleError { t =>
            val (out, err) = session.client.drainRunOutput()
            Task.pure(BspExecResult(
              projectRoot = input.projectRoot,
              status      = "ERROR",
              targetCount = resolved.size,
              stdout      = out.mkString,
              stderr      = (err.mkString + "\nBSP test dispatch failed: " + t.getMessage).trim
            ))
          }
        }
      }
    }
}
