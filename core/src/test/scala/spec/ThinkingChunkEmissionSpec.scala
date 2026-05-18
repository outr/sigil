package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{Signal, ThinkingChunk}
import sigil.tool.core.RespondTool
import sigil.tool.model.RespondInput
import sigil.transport.{ResumeRequest, SignalTransport}
import spice.http.HttpRequest

/**
 * Coverage for forwarding `ProviderEvent.ThinkingDelta` to consumers
 * as transient [[ThinkingChunk]] Notices. The orchestrator pre-
 * allocates a placeholder Message id on the first thinking chunk so
 * the `target` matches the eventual user-visible Message id; the
 * Message itself isn't emitted until the first user-visible content
 * delta lands (or, for tool-call-only respond paths, when the tool
 * builds its own Message).
 */
class ThinkingChunkEmissionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "thinking-model")

  private val reasoningChunks: List[String] = List(
    "Let me think about this... ",
    "The user wants a short greeting. ",
    "I should check tool availability. ",
    "Actually a direct respond is fine. ",
    "Going with a friendly reply."
  )

  /** Stub provider that streams reasoning chunks then a single
    * atomic `respond` tool call. Mirrors the llama.cpp / Kimi-with-
    * `reasoning_mode=on` shape that motivated the bug. */
  private class ReasoningThenRespondProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("stub provider — no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("respond-after-thinking")
      val thinking = reasoningChunks.map(t => ProviderEvent.ThinkingDelta(t): ProviderEvent)
      val toolFlow: List[ProviderEvent] = List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          callId,
          RespondInput(topicLabel = "Greeting", topicSummary = "User greeted the agent", content = "Hi there!", endsTurn = true)
        ),
        ProviderEvent.Done(StopReason.Complete)
      )
      Stream.emits(thinking ::: toolFlow)
    }
  }

  private val convId: Id[Conversation] = Conversation.id("thinking-chunk-conv")
  private val conv: Conversation = Conversation(topics = TestTopicStack, _id = convId)
  private val request: ConversationRequest = ConversationRequest(
    conversationId     = convId,
    modelId            = modelId,
    instructions       = Instructions(),
    turnInput          = TurnInput(conversationId = convId),
    currentMode        = ConversationMode,
    currentTopic       = TestTopicEntry,
    previousTopics     = Nil,
    generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
    chain              = List(TestUser, TestAgent),
    tools              = Vector(RespondTool)
  )

  /** Run one turn end-to-end and apply every Signal to `SigilDB` so
    * the persistence + replay assertions exercise the same path the
    * framework runs in production. `SigilDB.apply` no-ops on Notice
    * (Notices are transient — `Sigil.publish` skips persistence for
    * them), so ThinkingChunks pass through without writing rows. */
  private def runAndPersist(): Task[List[Signal]] =
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator
        .process(TestSigil, new ReasoningThenRespondProvider, request, conv)
        .evalMap(signal => TestSigil.withDB(_.apply(signal)).map(_ => signal))
        .toList
    } yield signals

  "ThinkingChunkEmissionSpec" should {

    "emit one ThinkingChunk per ProviderEvent.ThinkingDelta, all sharing the same target id" in {
      runAndPersist().map { signals =>
        val chunks = signals.collect { case t: ThinkingChunk => t }
        chunks should have size reasoningChunks.size
        chunks.map(_.delta) shouldBe reasoningChunks
        chunks.map(_.conversationId).distinct shouldBe List(convId)
        chunks.map(_.target).distinct should have size 1
      }
    }

    "match the eventual settled Message's id" in {
      runAndPersist().map { signals =>
        val chunks = signals.collect { case t: ThinkingChunk => t }
        chunks should not be empty
        val agentMessages = signals.collect {
          case m: Message if m.participantId == TestAgent && m.role == sigil.event.MessageRole.Standard => m
        }
        agentMessages should have size 1
        chunks.map(_.target).distinct shouldBe List(agentMessages.head._id)
      }
    }

    "not appear in SignalTransport.replay (Notice semantics — transient, no replay)" in {
      val transport = new SignalTransport(TestSigil)
      for {
        _ <- runAndPersist()
        replayed <- transport
          .replay(TestUser, ResumeRequest.RecentMessages(50), Some(Set(convId)))
          .toList
      } yield replayed.collect { case t: ThinkingChunk => t } shouldBe Nil
    }

    "not be persisted to SigilDB.events" in {
      for {
        _ <- runAndPersist()
        events <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val convEvents = events.filter(_.conversationId == convId)
        // ThinkingChunk is a Notice, not an Event — none should land
        // in the events store regardless of conversation filter.
        convEvents.exists(_.getClass.getSimpleName == "ThinkingChunk") shouldBe false
        // Sanity check: the agent's Message did persist, so the
        // assertion above isn't trivially true because nothing ran.
        convEvents.collect { case m: Message if m.participantId == TestAgent => m } should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
