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
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ChangeModeInput, ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Coverage for sigil bug #54 — the agent loop emits a per-iteration
 * boundary pulse between iterations of a multi-iteration self-loop, so
 * client UIs un-stick from `typing` (the last streamed activity) even
 * when the framework's outer claim spans multiple iterations.
 *
 * Sigil #349 — that pulse used to be `Idle → Thinking`, but `Idle` also
 * means "turn complete", so clients reset their per-turn UI on every tool
 * call. The boundary now emits the next iteration's real activity
 * (`Thinking`) directly, and `Idle` is emitted ONLY at the genuine turn
 * end: a multi-iteration turn produces exactly one `Idle`, carrying
 * `state = Complete`. The invariant is `Idle` ⇔ turn complete.
 *
 * The pulse mutates `AgentState.activity` only — `state` stays `Active`,
 * the claim is held across iterations as before.
 */
class AgentLoopIterationBoundarySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "iteration-boundary-model")
  TestSigil.testModel(modelId)

  /** Two-iteration provider: first call emits `change_mode` (a
    * non-terminal tool call that doesn't satisfy `userVisibleSeen`,
    * so the loop iterates), second call emits `respond`. The two
    * iterations run inside one outer claim; the per-iteration
    * boundary pulses must appear between them. */
  private class TwoIterationProvider extends Provider {
    private val callCount = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = callCount.incrementAndGet()
      val callId = CallId(s"call-$n")
      val events: List[ProviderEvent] =
        if (n == 1)
          // Iteration 1: change_mode (non-terminal, drives the next
          // iteration via TriggerFilter on ModeChange).
          List(
            ProviderEvent.ToolCallStart(callId, "change_mode"),
            ProviderEvent.ToolCallComplete(callId, ChangeModeInput(mode = "coding")),
            ProviderEvent.Done(StopReason.ToolCall)
          )
        else
          // Iteration 2: respond (terminal).
          List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(
              callId,
              RespondInput(topicLabel = "Test", topicSummary = "Iteration boundary repro", content = "Hi.", endsTurn = true)
            ),
            ProviderEvent.Done(StopReason.Complete)
          )
      Stream.emits(events)
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = ToolName("change_mode") :: CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def runScenario(): Task[List[Signal]] = {
    // Provider is genuinely stateful across the two-iteration scenario —
    // call 1 returns change_mode, call 2 returns respond. `setProvider`
    // re-evaluates its by-name argument on every `providerFor` call, so
    // we need to hand it a Task that holds a SINGLE provider instance
    // (otherwise each iteration gets a fresh callCount=0 and the loop
    // never reaches the terminal respond, eventually hitting the
    // maxAgentIterations cap).
    val provider = new TwoIterationProvider
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"iteration-boundary-${rapid.Unique()}")
    val agent  = makeAgent()
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running  = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()

    // Wait for the agent loop to settle — poll for the terminal
    // AgentStateDelta(Idle, Complete) that releaseClaim emits. Bounded
    // at 10 s to keep test runtime predictable; pre-fix the test slept
    // 1500 ms and missed the terminal delta when the iter cap raised
    // from 10 to 200 (sigil bug #109).
    def waitForTerminal(deadline: Long): Task[Unit] = Task.defer {
      val terminal = recorded.iterator().asScala.exists {
        case d: AgentStateDelta if d.activity.contains(AgentActivity.Idle)
                                && d.state.contains(EventState.Complete)
                                && d.conversationId == convId => true
        case _ => false
      }
      if (terminal) Task.unit
      else if (System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(50.millis).flatMap(_ => waitForTerminal(deadline))
    }

    for {
      _ <- Task.sleep(100.millis)
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Switch to coding then say hi.")),
             state          = EventState.Complete
           ))
      _ <- waitForTerminal(System.currentTimeMillis() + 10_000L)
    } yield {
      running.set(false)
      recorded.iterator().asScala.toList
    }
  }

  "Sigil.runAgentLoop (bug #54 / #349)" should {

    "emit NO mid-turn Idle, and exactly one terminal Idle, across a multi-iteration turn (#349)" in {
      runScenario().map { signals =>
        val idleDeltas = signals.collect {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Idle) => d
        }
        // #349 — `Idle` ⇔ turn complete. The boundary pulse no longer
        // reuses `Idle` (it emits `Thinking`), so a two-iteration turn
        // produces exactly one Idle delta, and it carries state=Complete
        // (the terminal release). A mid-turn Idle (state empty) is the bug.
        withClue(s"idle deltas (state): ${idleDeltas.map(_.state).mkString(", ")}: ") {
          idleDeltas.count(_.state.isEmpty) shouldBe 0
          idleDeltas should have size 1
          idleDeltas.head.state shouldBe Some(EventState.Complete)
        }
      }
    }

    "emit AgentStateDelta(Thinking) at the start of subsequent iterations" in {
      runScenario().map { signals =>
        val thinkingDeltas = signals.collect {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Thinking) => d
        }
        // The boundary pulse (#54's intent) now emits Thinking to un-stick
        // the consumer from `typing` without the overloaded Idle (#349) —
        // at least one such Delta on a multi-iteration turn.
        thinkingDeltas should not be empty
      }
    }

    "still emit a terminal AgentStateDelta(Idle, Complete) when the outer loop releases the claim" in {
      runScenario().map { signals =>
        val terminalIdle = signals.reverseIterator.collectFirst {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Idle)
                                  && d.state.contains(EventState.Complete) => d
        }
        terminalIdle should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
