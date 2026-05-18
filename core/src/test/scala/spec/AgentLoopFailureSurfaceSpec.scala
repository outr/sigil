package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType
}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Regression for bug #6 — when the agent loop throws mid-turn, the
 * framework surfaces the failure to the conversation rather than
 * leaving clients stuck on a "still typing" indicator that never
 * settles.
 *
 * Drives the full publish → runAgent → runAgentLoop pipeline against
 * a provider that throws in `call`. Verifies:
 *   1. A `Failure`-content Message lands in the conversation (so the
 *      UI renders a red error bubble).
 *   2. The agent's claim is released via an `Idle` AgentStateDelta
 *      so the activity indicator stops.
 */
class AgentLoopFailureSurfaceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /**
   * Provider whose `call` raises before emitting any event. The
   * orchestrator's stream evaluates lazily, so the error fires once
   * `runAgentLoop` starts draining — exactly the path the fix
   * targets.
   */
  private class CrashingProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.force(Task.error(new RuntimeException("simulated provider crash")))
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def runScenario(): Task[List[Signal]] = {
    TestSigil.setProvider(Task.pure(new CrashingProvider))
    val convId = Conversation.id(s"failure-surface-${rapid.Unique()}")
    val agent = makeAgent()
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()

    // Poll the recorder until BOTH the agent's Failure-disposition Message
    // and an Idle AgentStateDelta have landed (the two surfaces every
    // spec in this suite asserts on), or a 10s deadline elapses.
    // Replaces a fixed 800ms sleep that was tight enough to flake on
    // slow CI runners where the publish-then-record path can take a
    // full second under contention.
    def settled: Boolean = {
      val snapshot = recorded.iterator().asScala.toList
      val hasFailure = snapshot.exists {
        case m: Message if m.participantId == TestAgent && m.isFailure => true
        case _ => false
      }
      val hasIdle = snapshot.exists {
        case d: AgentStateDelta
            if d.activity.contains(AgentActivity.Idle) && d.state.contains(EventState.Complete) => true
        case _ => false
      }
      hasFailure && hasIdle
    }
    def waitForSettle(deadline: Long): Task[Unit] =
      if (settled || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(50.millis).flatMap(_ => waitForSettle(deadline))

    for {
      _ <- Task.sleep(100.millis)
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("hi")),
        state = EventState.Complete
      ))
      _ <- waitForSettle(System.currentTimeMillis() + 10_000L)
    } yield {
      running.set(false)
      recorded.iterator().asScala.toList
    }
  }

  "Sigil.runAgentLoop (bug #6)" should {
    "publish a Failure-disposition Message when a turn throws" in
      runScenario().map { signals =>
        val agentMessages = signals.collect {
          case m: Message if m.participantId == TestAgent => m
        }
        val failures = agentMessages.filter(_.isFailure)
        failures should not be empty
        // The reason carries the simulated exception's class+message
        // so operators have something to act on.
        val reasons = failures.flatMap(_.failureReason).mkString(" | ")
        reasons should include("simulated provider crash")
        // Surfaced as non-recoverable — the agent crashed, retrying
        // the same input on the same conversation isn't a sensible
        // automatic recovery.
        failures.exists(_.disposition match {
          case sigil.event.MessageDisposition.Failure(rec, _) => rec == false
          case _ => false
        }) shouldBe true
      }

    "settle the agent state to Idle so clients stop showing the activity indicator" in
      runScenario().map { signals =>
        val idleDeltas = signals.collect {
          case d: AgentStateDelta
              if d.activity.contains(AgentActivity.Idle)
                && d.state.contains(EventState.Complete) => d
        }
        idleDeltas should not be empty
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
