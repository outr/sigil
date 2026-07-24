package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.{Message, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, TokenUsage
}
import sigil.signal.{ConversationCostUpdated, EventState, Signal}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Per-iteration usage on tool-calling iterations must survive to both
 * cost surfaces. The provider contract is deltas → completes → Usage
 * → Done; a tool-call-only iteration's Usage then folds onto the
 * settled invoke (via the trailing ToolDelta) and the cost projection
 * charges it — so a long multi-iteration turn moves
 * [[ConversationCostUpdated]] incrementally, not only when a final
 * respond lands a Message. Field failure: a provider emitting Usage
 * BEFORE its tool-call flush starved both surfaces — every tool
 * iteration billed as zero and the user watched a 39-iteration repair
 * run with the cost badge frozen.
 *
 * Verifies:
 *   1. A tool-calling iteration's usage folds onto the persisted
 *      ToolInvoke.
 *   2. Each iteration fires its own ConversationCostUpdated with the
 *      iteration's charge — cost moves DURING the turn.
 */
class ToolIterationUsageCostSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val pricing: ModelPricing = ModelPricing(
    prompt = BigDecimal("0.000001"),
    completion = BigDecimal("0.000002"),
    webSearch = None,
    inputCacheRead = None
  )
  private val pricedModelId: Id[Model] = Model.id("test", "iter-usage-model")
  TestSigil.cache.merge(List(Model(
    canonicalSlug = "test/iter-usage-model",
    huggingFaceId = "",
    name = "iter-usage-model",
    description = "Synthetic priced model for per-iteration usage tests",
    contextLength = 32768L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = pricing,
    topProvider = ModelTopProvider(contextLength = Some(32768L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = pricedModelId
  ))).sync()

  private object NoExtraction extends sigil.conversation.compression.extract.MemoryExtractor {
    override def extract(sigil: _root_.sigil.Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[_root_.sigil.participant.ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[_root_.sigil.conversation.ContextMemory]] =
      Task.pure(Nil)
  }

  /**
   * Iteration 1: a pure tool call (no streamed text) with usage
   * emitted AFTER the tool-call complete — the normalized provider
   * ordering. Iteration 2: a terminating respond with its own usage.
   */
  final private class ToolThenRespondProvider extends Provider {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId(s"call-${rapid.Unique()}")
      val emits: List[ProviderEvent] =
        if (calls.incrementAndGet() == 1)
          List(
            ProviderEvent.ToolCallStart(callId, GetMagicNumberTool.name.value),
            ProviderEvent.ToolCallComplete(callId, GetMagicNumberInput()),
            ProviderEvent.Usage(TokenUsage(1000, 50, 1050)),
            ProviderEvent.Done(StopReason.ToolCall)
          )
        else
          List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(
              callId,
              RespondInput(
                topicLabel = TestTopicEntry.label,
                topicSummary = TestTopicEntry.summary,
                content = "The magic number is 42.",
                endsTurn = true
              )),
            ProviderEvent.Usage(TokenUsage(2000, 25, 2025)),
            ProviderEvent.Done(StopReason.ToolCall)
          )
      Stream.emits(emits)
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = pricedModelId,
      toolNames = CoreTools.coreToolNames :+ GetMagicNumberTool.name,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def waitFor(timeout: FiniteDuration)(cond: => Boolean): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] =
      if (cond || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    loop
  }

  "per-iteration usage on a tool-calling turn" should {

    "fold onto the invoke and move ConversationCostUpdated during the turn" in {
      val provider = new ToolThenRespondProvider
      TestSigil.setProvider(Task.pure(provider))
      TestSigil.setMemoryExtractor(NoExtraction)
      val convId = Conversation.id(s"iter-usage-${rapid.Unique()}")
      val agent = makeAgent()
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestSigil.signals
        .takeWhile(_ => running)
        .evalMap(s => Task { recorded.add(s); () })
        .drain
        .startUnit()

      def costNotices: List[ConversationCostUpdated] =
        recorded.iterator().asScala.collect {
          case n: ConversationCostUpdated if n.conversationId == convId => n
        }.toList

      val toolCharge = pricing.prompt * 1000 + pricing.completion * 50
      val respondCharge = pricing.prompt * 2000 + pricing.completion * 25

      for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("What is the magic number?")),
          state = EventState.Complete
        ))
        _ <- waitFor(20.seconds)(costNotices.size >= 2)
        events <- TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.filter(_.conversationId == convId))
      } yield {
        running = false
        val invoke = events.collect {
          case ti: ToolInvoke if ti.toolName == GetMagicNumberTool.name => ti
        }.headOption.getOrElse(fail("get_magic_number invoke not persisted"))
        withClue(s"invoke.usage=${invoke.usage}: ") {
          invoke.usage.totalTokens shouldBe 1050
        }
        val notices = costNotices
        withClue(s"cost notices: ${notices.map(_.delta)}: ") {
          notices.size should be >= 2
          // The tool iteration charged on its own — the badge moved
          // BEFORE the final respond landed.
          notices.map(_.delta) should contain(toolCharge)
          notices.map(_.delta) should contain(respondCharge)
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
