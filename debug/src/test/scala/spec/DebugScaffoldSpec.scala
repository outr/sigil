package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{GlobalSpace, SpaceId}
import sigil.debug.*

/**
 * Smoke check for the `sigil-debug` scaffold — every tool input
 * case class round-trips through its derived RW. Doesn't spawn any
 * adapter (those need a real debugpy / dlv / sbt-debug install);
 * the actual DAP request paths are exercised by integration specs
 * apps add when they wire a concrete adapter.
 */
class DebugScaffoldSpec extends AnyWordSpec with Matchers {
  SpaceId.register(RW.static[SpaceId](GlobalSpace))

  private def roundTrip[T: RW](value: T): Unit = {
    val rw = summon[RW[T]]
    rw.write(rw.read(value)) shouldBe value
  }

  "DebugAdapterConfig" should {
    "round-trip via its derived RW" in {
      roundTrip(DebugAdapterConfig(
        languageId = "python",
        command = "python",
        args = List("-m", "debugpy", "--listen", "5678"),
        launchType = "launch"
      ))
    }
  }

  "DAP tool inputs" should {
    "round-trip every shape the framework ships" in {
      roundTrip(DapLaunchInput(
        languageId = "scala",
        sessionId = "demo",
        launchArguments = Map("mainClass" -> fabric.str("Main")),
        breakpointsByFile = Map("/abs/Foo.scala" -> List(15, 32))
      ))
      roundTrip(DapAttachInput(languageId = "scala", sessionId = "demo"))
      roundTrip(DapSetBreakpointsInput(sessionId = "demo", filePath = "/abs/Foo.scala", lines = List(15)))
      roundTrip(DapSetExceptionBreakpointsInput(sessionId = "demo", filters = List("uncaught")))
      roundTrip(DapContinueInput(sessionId = "demo", threadId = 1))
      roundTrip(DapStepOverInput(sessionId = "demo", threadId = 1))
      roundTrip(DapStepInInput(sessionId = "demo", threadId = 1))
      roundTrip(DapStepOutInput(sessionId = "demo", threadId = 1))
      roundTrip(DapPauseInput(sessionId = "demo", threadId = 1))
      roundTrip(DapThreadsInput(sessionId = "demo"))
      roundTrip(DapStackTraceInput(sessionId = "demo", threadId = 1))
      roundTrip(DapScopesInput(sessionId = "demo", frameId = 1000))
      roundTrip(DapVariablesInput(sessionId = "demo", variablesReference = 1001))
      roundTrip(DapEvaluateInput(sessionId = "demo", expression = "x + 1", frameId = Some(1000)))
      roundTrip(DapSessionStatusInput(sessionId = "demo", waitForStopMs = 5000))
      roundTrip(DapListSessionsInput())
      roundTrip(DapDisconnectInput(sessionId = "demo", terminateDebuggee = true))
    }
  }
}
