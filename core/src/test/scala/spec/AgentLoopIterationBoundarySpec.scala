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
 * Coverage for sigil bug #54 — the agent loop now emits
 * `AgentStateDelta(Idle)` + `AgentStateDelta(Thinking)` between
 * iterations of a multi-iteration self-loop, so client UIs can
 * render turn-boundary state transitions even when the framework's
 * outer claim spans multiple iterations.
 *
 * Without these per-iteration pulses the consumer's activity
 * indicator pinned at `typing` (the last emitted activity in the
 * prior iteration's streaming) until the entire outer loop
 * completed. For long multi-iteration runs (e.g., a turn that
 * imports thousands of historical events and then iterates once
 * more on the tool-result trigger) this looked like the agent had
 * hung.
 *
 * The pulses mutate `AgentState.activity` only — `state` stays
 * `Active`, the claim is held across iterations as before.
 */
class AgentLoopIterationBoundarySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "iteration-boundary-model")

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
            ProviderEvent.ToolCallComplete(callId, ChangeModeInput(mode = "conversation")),
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
    TestSigil.setProvider(Task.pure(new TwoIterationProvider))
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
      _ <- Task.sleep(1500.millis)
    } yield {
      running.set(false)
      recorded.iterator().asScala.toList
    }
  }

  "Sigil.runAgentLoop (bug #54)" should {

    "emit AgentStateDelta(Idle) without state=Complete between iterations" in {
      runScenario().map { signals =>
        val nonTerminalIdle = signals.collect {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Idle) && d.state.isEmpty => d
        }
        // At least one inter-iteration idle pulse (between iter 1
        // and iter 2). Multi-iteration self-loops may emit more.
        nonTerminalIdle should not be empty
      }
    }

    "emit AgentStateDelta(Thinking) at the start of subsequent iterations" in {
      runScenario().map { signals =>
        val thinkingDeltas = signals.collect {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Thinking) => d
        }
        // Iteration 1's thinking comes from tryFire's AgentState
        // event (not a Delta). Iteration 2+'s thinking comes from
        // the new per-iteration pulse — at least one Delta.
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

    "preserve the order: per-iteration Idle precedes per-iteration Thinking" in {
      runScenario().map { signals =>
        // Find the position of the first non-terminal Idle and the
        // first non-terminal Thinking Delta. The Idle should come
        // first — the loop emits "iter N ended (idle)" before "iter
        // N+1 starting (thinking)".
        val idx = signals.zipWithIndex.collect {
          case (d: AgentStateDelta, i) if d.activity.contains(AgentActivity.Idle) && d.state.isEmpty =>
            ("idle", i)
          case (d: AgentStateDelta, i) if d.activity.contains(AgentActivity.Thinking) =>
            ("thinking", i)
        }
        val firstIdleIdx     = idx.collectFirst { case ("idle", i) => i }
        val firstThinkingIdx = idx.collectFirst { case ("thinking", i) => i }
        (firstIdleIdx, firstThinkingIdx) match {
          case (Some(i), Some(j)) => i should be < j
          case _ => fail(s"expected both idle and thinking deltas; got ${idx.mkString(", ")}")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
