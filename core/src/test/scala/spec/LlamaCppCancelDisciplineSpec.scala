package spec

import org.scalatest.{Assertion, BeforeAndAfterAll}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Stop
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{GenerationSettings, Instructions, Provider}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.testkit.{ConversationHarness, ConversationSession}
import sigil.tool.core.CoreTools

import scala.concurrent.duration.*

/**
 * End-to-end discipline check — an agent driven through a normal
 * multi-step prompt must not call `cancel` as a turn-flow operation.
 *
 * Live-only: skips cleanly when `TestSigil.llamaCppHost` is
 * unreachable (the harness's `start()` raises, which we treat as
 * an environmental skip rather than a test failure).
 *
 * The spec verifies the framework-side contract — that prompt +
 * tool description + validation collectively keep the model off
 * `cancel` for non-cancellation use cases. The deterministic
 * coverage of the validator itself lives in
 * [[CancelToolValidationSpec]]; this spec checks that with the
 * canonical `CancelTool` in the roster, a small local model
 * doesn't reach for it mid-task.
 */
class LlamaCppCancelDisciplineSpec
  extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {

  override implicit protected val testTimeout: FiniteDuration = 3.minutes

  TestSigil.initFor(getClass.getSimpleName)

  private val provider: Task[Provider] =
    LlamaCppProvider(TestSigil, TestSigil.llamaCppHost).singleton

  private val modelId = Model.id("qwen3.5-9b-q4_k_m")

  TestSigil.setProvider(provider)

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                = TestAgent,
      modelId           = modelId,
      toolNames         = CoreTools.coreToolNames,
      instructions      = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(2000), temperature = Some(0.0))
    )

  private lazy val harness: ConversationHarness =
    ConversationHarness(
      sigil  = TestSigil,
      viewer = TestUser,
      conversationFactory = convId => Conversation(
        topics       = List(TestTopicEntry),
        _id          = convId,
        participants = List(makeAgent())
      )
    )

  private var harnessStarted: Boolean = false

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    try {
      harness.start().sync()
      harnessStarted = true
    } catch {
      case t: Throwable =>
        scribe.warn(s"LlamaCppCancelDisciplineSpec: skipping — harness.start failed: ${t.getMessage}")
    }
  }

  override protected def afterAll(): Unit = {
    if (harnessStarted) harness.stop().sync()
    super.afterAll()
  }

  private def skipIfNoHarness(body: => Task[Assertion]): Task[Assertion] =
    if (harnessStarted) body
    else Task.pure(cancel("llama.cpp endpoint unreachable — skipping live discipline check"))

  "agent driven through a normal multi-step prompt" should {

    "never call cancel mid-task (no Stop event in the wire-received stream)" in skipIfNoHarness {
      harness.withClient("cancel-discipline") { s =>
        for {
          // Multi-step prompt — should exercise tool calls + respond, not cancel.
          _ <- s.send("Explain in two short sentences what the word 'idempotent' means in software engineering.")
          _ <- s.send("Now give one concrete example of an idempotent HTTP method.")
        } yield assertNoStop(s)
      }
    }

    "never call cancel when asked to perform a deliberate transition" in skipIfNoHarness {
      // Reproduces the bug-report shape: a user message that an
      // agent might misread as "checkpoint and cancel here".
      harness.withClient("cancel-transition") { s =>
        for {
          _ <- s.send("I'd like to evaluate the password-reset flow. First, summarize what you'd check.")
        } yield assertNoStop(s)
      }
    }
  }

  private def assertNoStop(s: ConversationSession): Assertion = {
    val stops = s.received.all.collect { case st: Stop => st }
    withClue(s"agent must not call cancel mid-task; got ${stops.size} Stop event(s): ${stops.map(_.reason)}") {
      stops shouldBe empty
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
