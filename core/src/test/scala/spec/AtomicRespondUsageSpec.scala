package spec

import lightdb.id.Id
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason, TokenUsage
}
import sigil.signal.{EventState, MessageDelta, Signal}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseDisposition, RespondInput}
import spice.http.HttpRequest

/**
 * Regression for Sigil bug #179 — atomic-respond turns (no streaming
 * ContentBlockDelta path) were dropping the trailing
 * `ProviderEvent.Usage` because the orchestrator's Usage handler
 * targets `state.activeMessageId.orElse(state.lastUserVisibleMessageId)`
 * and neither was set when atomic respond fires.
 *
 * The fix already in `tracked.evalMap` updates `lastUserVisibleMessageId`
 * when the tool emits a non-Tool-role Message. This spec confirms the
 * end-to-end path: atomic respond → ToolCallComplete → tracked stream
 * sets `lastUserVisibleMessageId` → Usage event fires
 * `MessageDelta(usage=non-zero)` targeting the right Message.
 */
class AtomicRespondUsageSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "atomic-model")

  /** Provider that emits the atomic respond shape: ToolCallStart, then
    * ToolCallComplete (NO ContentBlockDelta — args arrived as a
    * single payload, never streamed character-by-character through
    * `RespondStreamProcessor`), then Done, then a trailing Usage event. */
  private class AtomicRespondProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("respond-atomic")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          callId,
          RespondInput(
            topicLabel    = "Greeting",
            topicSummary  = "Hello world",
            content       = "Hello, world.",
            disposition   = ResponseDisposition.Success,
            endsTurn      = true,
            keywords      = Nil
          )
        ),
        ProviderEvent.Done(StopReason.Complete),
        ProviderEvent.Usage(TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 120))
      ))
    }
  }

  "Bug #179 — atomic respond + trailing Usage" should {

    "fire a MessageDelta targeting the user-visible Message with non-zero usage" in {
      val convId = Conversation.id(s"atomic-usage-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = ConversationRequest(
        conversationId     = convId,
        modelId            = modelId,
        instructions       = Instructions(),
        turnInput          = TurnInput(conversationId = convId),
        currentMode        = ConversationMode,
        currentTopic       = TestTopicEntry,
        previousTopics     = Nil,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        chain              = List(TestUser, TestAgent),
        tools              = CoreTools.all.toVector
      )
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, new AtomicRespondProvider, request, conv).toList
      } yield {
        val messages = signals.collect {
          case m: Message
            if m.participantId == TestAgent &&
              m.role == sigil.event.MessageRole.Standard &&
              m.conversationId == convId => m
        }
        messages should not be empty
        val msgId = messages.head._id

        // The trailing Usage event should produce a MessageDelta with
        // non-zero usage, targeting the user-visible Message.
        val usageDeltas = signals.collect {
          case md: MessageDelta if md.target == msgId && md.usage.isDefined => md
        }
        usageDeltas should not be empty
        val u = usageDeltas.head.usage.value
        u.promptTokens shouldBe 100
        u.completionTokens shouldBe 20
        u.totalTokens shouldBe 120
      }
    }

    "land non-zero usage on the persisted Message after the full publish pipeline runs" in {
      val convId = Conversation.id(s"atomic-usage-persist-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = ConversationRequest(
        conversationId     = convId,
        modelId            = modelId,
        instructions       = Instructions(),
        turnInput          = TurnInput(conversationId = convId),
        currentMode        = ConversationMode,
        currentTopic       = TestTopicEntry,
        previousTopics     = Nil,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        chain              = List(TestUser, TestAgent),
        tools              = CoreTools.all.toVector
      )
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, new AtomicRespondProvider, request, conv).toList
        // Publish every signal in order — the live pipeline.
        _       <- signals.foldLeft(Task.unit) { (acc, s) =>
                     acc.flatMap(_ => TestSigil.publish(s).handleError(_ => Task.unit))
                   }
        evs     <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val persisted = evs.collect {
          case m: Message
            if m.conversationId == convId &&
              m.participantId == TestAgent &&
              m.role == sigil.event.MessageRole.Standard => m
        }
        persisted should not be empty
        val u = persisted.head.usage
        u.promptTokens shouldBe 100
        u.completionTokens shouldBe 20
        u.totalTokens shouldBe 120
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
