package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, ConversationMode, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal}
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, NoResponseTool, RespondTool}
import sigil.tool.model.{NoResponseInput, RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Regression for bug #46 — when the agent loop terminates without
 * the agent ever calling a user-visible terminal tool (`respond`,
 * `respond_options`, `respond_field`, `respond_failure`,
 * `no_response`), `Sigil.runAgentLoop` synthesizes a placeholder
 * Message so the conversation doesn't go silent.
 *
 * Drives the full publish → runAgent → runAgentLoop pipeline against
 * a fake provider — the synthesis lives at the loop level (not
 * orchestrator level) because a single iteration ending with a
 * non-terminal tool call (e.g. `change_mode`, `find_capability`) is
 * legitimate mid-task; only the loop knows the conversation is
 * actually closing.
 */
class OrchestratorSilentTurnSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /** Provider that calls a non-terminal tool then emits Done. After
    * the orchestrator forwards the events, runAgentLoop checks for
    * new triggers and finds none — at which point the silent-turn
    * fallback should fire. */
  private class SilentlyCompletingProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("silent-call")
      // Use NoResponseTool's input shape but as a non-recognized tool
      // name — the orchestrator will see ToolCallStart for an unknown
      // tool and route through executeAtomic with no match (returns
      // empty), producing no further events. Net effect: the loop
      // sees no user-visible terminal tool, so the silent-turn
      // fallback should fire.
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "not_a_terminal_tool"),
        ProviderEvent.ToolCallComplete(callId, NoResponseInput()),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Provider that calls `respond` with real content then Done.
    * Negative case — synthesis should NOT happen. */
  private class RespondingProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("respond-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(callId,
          RespondInput(topicLabel = "Test", topicSummary = "Test summary", content = "Done.")),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Provider that calls `no_response` then Done. Negative case —
    * `no_response` counts as user-visible terminal so no synthesis. */
  private class NoResponseProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("noresp-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, NoResponseTool.schema.name.value),
        ProviderEvent.ToolCallComplete(callId, NoResponseInput()),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def runScenario(provider: Provider, suffix: String): Task[List[Signal]] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"silent-turn-loop-$suffix")
    val agent = makeAgent()
    val conv  = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running  = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()

    for {
      _ <- Task.sleep(100.millis) // give the subscriber a moment to attach
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      // Trigger the agent loop by publishing a user Message in the
      // conversation. `publish` runs the agent dispatch (claim → run
      // → release), so by the time we sleep below the loop has
      // settled.
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("hi")),
             state          = EventState.Complete
           ))
      // The agent loop runs on a background fiber. Wait for it to
      // settle — local fake provider is fast, 800ms is plenty.
      _ <- Task.sleep(800.millis)
    } yield {
      running.set(false)
      recorded.iterator().asScala.toList
    }
  }

  "Sigil.runAgentLoop (bug #46)" should {
    "synthesize a placeholder Message when the loop ends without a user-visible terminal tool" in {
      runScenario(new SilentlyCompletingProvider, suffix = "silent").map { signals =>
        val agentMessages = signals.collect {
          case m: Message if m.participantId == TestAgent => m
        }
        val placeholder = agentMessages.find(_.content.exists {
          case ResponseContent.Text(t) => t.contains("without a reply")
          case _                       => false
        })
        placeholder should not be empty
        placeholder.get.state shouldBe EventState.Complete
      }
    }

    "not synthesize a placeholder when the agent called respond" in {
      runScenario(new RespondingProvider, suffix = "responding").map { signals =>
        val syntheticMessages = signals.collect {
          case m: Message if m.participantId == TestAgent &&
            m.content.exists {
              case ResponseContent.Text(t) => t.contains("without a reply")
              case _                       => false
            } => m
        }
        syntheticMessages shouldBe empty
      }
    }

    "not synthesize a placeholder when the agent called no_response" in {
      runScenario(new NoResponseProvider, suffix = "noresp").map { signals =>
        val syntheticMessages = signals.collect {
          case m: Message if m.participantId == TestAgent &&
            m.content.exists {
              case ResponseContent.Text(t) => t.contains("without a reply")
              case _                       => false
            } => m
        }
        syntheticMessages shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
