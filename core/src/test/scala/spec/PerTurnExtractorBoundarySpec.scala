package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextMemory, Conversation}
import sigil.conversation.compression.extract.MemoryExtractor
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant, ParticipantId}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ChangeModeInput, ResponseContent, RespondContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Regression for sigil bug #149 — the per-turn memory extractor
 * used to fire from `Orchestrator.process`'s `Done` handler, which
 * runs once per agent-loop ITERATION. A multi-iteration turn (any
 * tool-call sequence) produced N extraction calls + N duplicate
 * memory writes.
 *
 * Fix: lift the extractor call from Orchestrator into
 * `Sigil.runAgentLoop`'s `terminate()` helper, gated by a
 * turn-scoped `AtomicBoolean` threaded across every recursion.
 * Exactly one fire per user turn, at the boundary, with
 * `userMessage` + `agentResponse` assembled from the
 * conversation's events since `claimed.timestamp`.
 *
 * Integration test: drives a 2-iteration agent loop (change_mode
 * → respond) through `runAgentLoop`, asserts the counting
 * extractor fired exactly once.
 */
class PerTurnExtractorBoundarySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "per-turn-extractor-model")

  /** Counts every extractor call so the spec can prove the fix
    * collapses N per-iteration fires into exactly one. */
  private final class CountingExtractor extends MemoryExtractor {
    val calls = new atomic.AtomicInteger(0)
    val lastUser = new atomic.AtomicReference[String]("")
    val lastAgent = new atomic.AtomicReference[String]("")
    override def extract(sigil: Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[ContextMemory]] = Task {
      calls.incrementAndGet()
      lastUser.set(userMessage)
      lastAgent.set(agentResponse)
      Nil
    }
  }

  /** Two-iteration provider: change_mode then respond. Forces the
    * agent loop through ≥ 2 iterations under one outer claim. */
  private class TwoIterationProvider extends Provider {
    private val callCount = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = callCount.incrementAndGet()
      val callId = CallId(s"call-$n")
      val events: List[ProviderEvent] =
        if (n == 1)
          List(
            ProviderEvent.ToolCallStart(callId, "change_mode"),
            ProviderEvent.ToolCallComplete(callId, ChangeModeInput(mode = "conversation")),
            ProviderEvent.Done(StopReason.ToolCall)
          )
        else
          List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(
              callId,
              RespondInput(topicLabel = "Test", topicSummary = "Per-turn extractor", content = RespondContent.Text("Reply"), endsTurn = true)
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

  /** Wait for the outer claim's terminal `AgentStateDelta(Idle,
    * Complete)` — fires from `releaseClaim` at the loop's
    * terminate point. The post-terminate extractor fiber runs
    * concurrently; a short additional sleep gives it room to
    * complete its (synchronous, in-memory) counter increment. */
  private def waitForTerminal(recorded: ConcurrentLinkedQueue[Signal],
                              convId: Id[Conversation],
                              deadline: Long): Task[Unit] = Task.defer {
    val terminal = recorded.iterator().asScala.exists {
      case d: AgentStateDelta if d.activity.contains(AgentActivity.Idle)
                              && d.state.contains(EventState.Complete)
                              && d.conversationId == convId => true
      case _ => false
    }
    if (terminal) Task.sleep(500.millis)  // let the extractor fiber settle
    else if (System.currentTimeMillis() > deadline) Task.unit
    else Task.sleep(50.millis).flatMap(_ => waitForTerminal(recorded, convId, deadline))
  }

  "Per-turn memory extractor" should {

    "fire exactly once across a multi-iteration agent loop" in {
      TestSigil.reset()
      val extractor = new CountingExtractor
      TestSigil.setMemoryExtractor(extractor)
      val provider = new TwoIterationProvider
      TestSigil.setProvider(Task.pure(provider))

      val convId = Conversation.id(s"per-turn-${rapid.Unique()}")
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
        _ <- waitForTerminal(recorded, convId, System.currentTimeMillis() + 10_000L)
      } yield {
        running.set(false)
        // The whole point of bug #149: across two iterations of the
        // loop, the extractor fires exactly ONCE at the terminate
        // boundary. Pre-fix this would have been 2 (one per
        // iteration's Done event).
        extractor.calls.get() shouldBe 1
        // The captured args come from the conversation's events
        // since claim — the user's text frame survives.
        extractor.lastUser.get() should include("Switch to coding")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
