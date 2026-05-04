package spec

import java.util.concurrent.atomic.AtomicInteger

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.{Sigil, TurnContext}
import sigil.conversation.{Conversation, ConversationView, TopicEntry, Topic, TurnInput}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.{Event, MessageRole}
import sigil.participant.ParticipantId
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings, Instructions, Provider,
  ProviderCall, ProviderEvent, ProviderRequest, ProviderType, RequestOverBudgetException, StopReason
}
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import sigil.tool.Tool
import spice.http.HttpRequest

/**
 * Coverage for [[Provider]]'s pre-flight budget gate. A fake provider
 * with a tiny [[Model.contextLength]] makes the gate visible:
 *
 *   - A request that fits goes through unchanged.
 *   - A request modestly over budget is emergency-shed (tool roster
 *     trim → drop oldest frames) and goes through.
 *   - A request fundamentally too large produces
 *     [[RequestOverBudgetException]] with diagnostics.
 *
 * The fake provider records every `ProviderCall` it receives so we
 * can inspect what shed down to.
 */
class PreFlightBudgetSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Id[Model]("test/preflight")

  // 2000-token model context — small enough that a multi-thousand-
  // token request blows past, large enough to leave room for the
  // ~370-token framework system-prompt overhead.
  private val miniModel: Model = Model(
    canonicalSlug = "test/preflight",
    huggingFaceId = "",
    name = "preflight",
    description = "Synthetic mini-context model for pre-flight gate tests",
    contextLength = 2000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(2000L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  )

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestSigil.cache.merge(List(miniModel)).sync()
  }

  private def buildOversizedSystemRequest(): ProviderRequest = {
    val convId = Conversation.id(s"preflight-oversized-${rapid.Unique()}")
    val topic = TopicEntry(Id[Topic]("test-topic"), "Test", "Synthetic")
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    val hugeInstructions = Instructions(
      behavior = "BEHAVIOR\n" + ("- The agent must follow this directive every turn. " * 800)
    )
    ConversationRequest(
      conversationId = convId,
      modelId = modelId,
      instructions = hugeInstructions,
      turnInput = TurnInput(view),
      currentMode = ConversationMode,
      currentTopic = topic,
      previousTopics = Nil,
      generationSettings = GenerationSettings(),
      tools = Vector.empty,
      chain = List(TestUser, TestAgent)
    )
  }

  private def buildRequest(systemFrameText: String): ProviderRequest = {
    val convId = Conversation.id(s"preflight-${rapid.Unique()}")
    val topic = TopicEntry(Id[Topic]("test-topic"), "Test", "Synthetic")
    val view = ConversationView(
      conversationId = convId,
      frames = if (systemFrameText.isEmpty) Vector.empty
               else Vector(sigil.conversation.ContextFrame.Text(systemFrameText, TestUser, Id[Event]())),
      _id = ConversationView.idFor(convId)
    )
    ConversationRequest(
      conversationId = convId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(view),
      currentMode = ConversationMode,
      currentTopic = topic,
      previousTopics = Nil,
      generationSettings = GenerationSettings(),
      tools = Vector.empty,
      chain = List(TestUser, TestAgent)
    )
  }

  "Provider.preFlightGate" should {

    "let a small request through unchanged" in {
      val provider = new RecordingStubProvider()
      val req = buildRequest("hi")
      provider.apply(req).toList.map { events =>
        events.collect { case _: ProviderEvent.Done => 1 }.sum shouldBe 1
        provider.lastCall.get() should not be null
        // No emergency shedding needed.
        succeed
      }
    }

    "fail with RequestOverBudgetException when fundamentally too large" in {
      val provider = new RecordingStubProvider()
      // Frames can be shed; the system prompt cannot. Stuff a huge
      // custom Behavior block into Instructions — that lands in the
      // system prompt and forces the post-shed total over the cap.
      val req = buildOversizedSystemRequest()
      provider.apply(req).toList.attempt.map {
        case scala.util.Failure(e: RequestOverBudgetException) =>
          e.modelId shouldBe modelId
          e.contextLength shouldBe 2000
          e.estimatedTokens should be > 2000
          succeed
        case scala.util.Failure(other) =>
          fail(s"Expected RequestOverBudgetException, got ${other.getClass.getSimpleName}: ${other.getMessage}")
        case scala.util.Success(events) =>
          fail(s"Expected exception, but provider received call with ${events.size} events")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

/** Fake provider that captures the [[ProviderCall]] it receives and
  * returns a single Done event. */
private class RecordingStubProvider extends Provider {
  val callCount: AtomicInteger = new AtomicInteger(0)
  val lastCall: java.util.concurrent.atomic.AtomicReference[ProviderCall] =
    new java.util.concurrent.atomic.AtomicReference(null)

  override def `type`: ProviderType = ProviderType.LlamaCpp
  override val providerKey: String = "test-stub"
  override def models: List[Model] = Nil
  override protected def sigil: Sigil = TestSigil
  override def tokenizer: Tokenizer = HeuristicTokenizer

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    Task.error(new UnsupportedOperationException("RecordingStubProvider"))

  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    callCount.incrementAndGet()
    lastCall.set(input)
    Stream.emit(ProviderEvent.Done(StopReason.Complete))
  }
}
