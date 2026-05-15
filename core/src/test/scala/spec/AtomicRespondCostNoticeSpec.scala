package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Message
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason, TokenUsage
}
import sigil.signal.{ConversationCostUpdated, Signal}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ChangeModeInput, ResponseDisposition, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt

/**
 * End-to-end regression for the cost-notice chain on agent turns —
 * the gap between "MessageDelta(usage) folds onto the persisted
 * Message" (covered by [[AtomicRespondUsageSpec]]) and
 * "applyMessageCostToConversation reads the folded usage and emits a
 * ConversationCostUpdated Notice" (covered by
 * [[MessageModelIdAndCostSpec]] for directly-published Messages, but
 * not through the orchestrator's emit-then-delta-settle path).
 *
 * Wire-log evidence: turns whose only output is a tool call (no
 * user-visible Message) attach the trailing Usage to a ToolInvoke
 * id via MessageDelta — and MessageDelta.apply on a ToolInvoke is
 * a no-op, so the usage data is lost and Conversation.cost never
 * advances for that turn.
 */
class AtomicRespondCostNoticeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val pricing: ModelPricing = ModelPricing(
    prompt = BigDecimal("0.000001"),
    completion = BigDecimal("0.000002"),
    webSearch = None,
    inputCacheRead = None
  )

  private val modelId: Id[Model] = Model.id("test", "cost-notice-spec-model")
  private val priced: Model = Model(
    canonicalSlug = "test/cost-notice-spec-model",
    huggingFaceId = "",
    name = "cost-notice-spec-model",
    description = "Synthetic priced model for cost-notice regression",
    contextLength = 131072L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = pricing,
    topProvider = ModelTopProvider(contextLength = Some(131072L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  )

  TestSigil.cache.merge(List(priced)).sync()

  private def buildRequest(convId: Id[Conversation]): ConversationRequest =
    ConversationRequest(
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

  private def seedConversation(convId: Id[Conversation]): Task[Conversation] = {
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_ => conv)
  }

  private def runAndCollectNotices(provider: Provider,
                                   convId: Id[Conversation],
                                   conv: Conversation,
                                   request: ConversationRequest): Task[List[ConversationCostUpdated]] = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    @volatile var running = true
    TestSigil.signals
      .takeWhile(_ => running)
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()

    for {
      _       <- Task.sleep(100.millis)
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
      _       <- signals.foldLeft(Task.unit) { (acc, s) =>
                   acc.flatMap(_ => TestSigil.publish(s).handleError(_ => Task.unit))
                 }
      _       <- Task.sleep(200.millis)
    } yield {
      running = false
      import scala.jdk.CollectionConverters.*
      recorded.iterator().asScala
        .collect { case n: ConversationCostUpdated if n.conversationId == convId => n }
        .toList
    }
  }

  "Cost-notice chain on atomic respond" should {

    "fire a ConversationCostUpdated Notice with non-zero delta when the turn emits respond + trailing Usage" in {
      val convId = Conversation.id(s"cost-notice-respond-${rapid.Unique()}")
      val request = buildRequest(convId)
      val provider: Provider = new Provider {
        override def `type`: ProviderType = ProviderType.LlamaCpp
        override def models: List[Model] = Nil
        override protected def sigil: _root_.sigil.Sigil = TestSigil
        override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
          Task.error(new UnsupportedOperationException("no wire"))
        override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.emits(List(
          ProviderEvent.ToolCallStart(CallId("respond-1"), RespondTool.schema.name.value),
          ProviderEvent.ToolCallComplete(
            CallId("respond-1"),
            RespondInput(
              topicLabel   = "Greeting",
              topicSummary = "Hello world",
              content      = "Hello.",
              disposition  = ResponseDisposition.Success,
              endsTurn     = true,
              keywords     = Nil
            )
          ),
          ProviderEvent.Done(StopReason.Complete),
          ProviderEvent.Usage(TokenUsage(promptTokens = 1000, completionTokens = 500, totalTokens = 1500))
        ))
      }

      val expectedDelta = pricing.prompt * 1000 + pricing.completion * 500

      for {
        conv    <- seedConversation(convId)
        notices <- runAndCollectNotices(provider, convId, conv, request)
        loaded  <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        withClue("conversation row should carry the accumulated cost: ") {
          loaded.map(_.cost) shouldBe Some(expectedDelta)
        }
        withClue(s"expected at least one ConversationCostUpdated notice, got: $notices: ") {
          notices should not be empty
        }
        notices.head.delta shouldBe expectedDelta
        notices.head.cost shouldBe expectedDelta
      }
    }

    "fire a ConversationCostUpdated Notice when the turn emits a non-respond tool call + trailing Usage (no user-visible Message)" in {
      // Wire-log scenario: the model issues change_mode and emits
      // Usage afterward without producing a respond. The orchestrator's
      // Usage handler falls into the `lastSettledInvokeId` branch and
      // emits MessageDelta(target=invokeId, usage=Some). Pre-fix,
      // MessageDelta.apply on a ToolInvoke is a no-op — the usage is
      // dropped and no cost notice fires.
      val convId = Conversation.id(s"cost-notice-toolcall-only-${rapid.Unique()}")
      val request = buildRequest(convId)
      val provider: Provider = new Provider {
        override def `type`: ProviderType = ProviderType.LlamaCpp
        override def models: List[Model] = Nil
        override protected def sigil: _root_.sigil.Sigil = TestSigil
        override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
          Task.error(new UnsupportedOperationException("no wire"))
        override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.emits(List(
          ProviderEvent.ToolCallStart(CallId("change-mode-1"), "change_mode"),
          ProviderEvent.ToolCallComplete(
            CallId("change-mode-1"),
            ChangeModeInput(mode = "conversation")
          ),
          ProviderEvent.Done(StopReason.ToolCall),
          ProviderEvent.Usage(TokenUsage(promptTokens = 2000, completionTokens = 25, totalTokens = 2025))
        ))
      }

      val expectedDelta = pricing.prompt * 2000 + pricing.completion * 25

      for {
        conv    <- seedConversation(convId)
        notices <- runAndCollectNotices(provider, convId, conv, request)
        loaded  <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        withClue("conversation row should carry the accumulated cost: ") {
          loaded.map(_.cost) shouldBe Some(expectedDelta)
        }
        withClue(s"expected at least one ConversationCostUpdated notice, got: $notices: ") {
          notices should not be empty
        }
        notices.head.delta shouldBe expectedDelta
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
