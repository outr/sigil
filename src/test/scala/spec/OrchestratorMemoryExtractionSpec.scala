package spec

import java.util.concurrent.atomic.AtomicReference
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, ConversationView, TurnInput}
import sigil.conversation.compression.extract.MemoryExtractor
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.{CallId, ConversationRequest, GenerationSettings, Instructions, Mode, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.orchestrator.Orchestrator
import spice.http.HttpRequest

/**
 * Wires a recording [[MemoryExtractor]] into TestSigil and verifies
 * the Orchestrator invokes it after `Done` on a background fiber.
 * The spec drives a canned provider stream that emits a respond
 * tool-call + streamed text + Done; the extractor records the
 * captured user / agent text for assertion.
 */
class OrchestratorMemoryExtractionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "orch-extract-model")

  TestSigil.withDB(_.model.transaction(_.upsert(Model(
    canonicalSlug = "test/orch-extract-model",
    huggingFaceId = "",
    name = "Test Orch Extract Model",
    description = "",
    contextLength = 1000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(1000L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  )))).sync()

  private class RecordingExtractor extends MemoryExtractor {
    val captured = new AtomicReference[Option[(String, String)]](None)
    override def extract(sigil: Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[ContextMemory]] = {
      captured.set(Some(userMessage -> agentResponse))
      Task.pure(Nil)
    }
  }

  private class StubProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: Sigil = TestSigil
    override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("StubProvider"))
    override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
      // Emit a text-only response (no respond tool call) so the
      // orchestrator's topic classifier path doesn't run — we just
      // want the turn-text accumulator + Done hook to trigger the
      // memory extractor.
      val callId = CallId("text-1")
      Stream.emits(List(
        ProviderEvent.ContentBlockStart(callId, "Text", None),
        ProviderEvent.ContentBlockDelta(callId, "Hello "),
        ProviderEvent.ContentBlockDelta(callId, "world"),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  "Orchestrator" should {
    "fire memoryExtractor after Done with last-user-text + accumulated agent text" in {
      TestSigil.reset()
      val recorder = new RecordingExtractor
      TestSigil.setMemoryExtractor(recorder)

      val convId = Conversation.id(s"orchex-${rapid.Unique()}")
      val userText = "I bought a new electric guitar yesterday at the Guitar Center downtown."
      val view = ConversationView(
        conversationId = convId,
        frames = Vector(ContextFrame.Text(
          content = userText,
          participantId = TestUser,
          sourceEventId = Id[Event]("seed")
        )),
        _id = ConversationView.idFor(convId)
      )
      val request = ConversationRequest(
        conversationId = convId,
        modelId = modelId,
        instructions = Instructions(),
        turnInput = TurnInput(view),
        currentMode = Mode.Conversation,
        currentTopic = TestTopicEntry,
        generationSettings = GenerationSettings(),
        tools = Vector(sigil.tool.core.RespondTool),
        chain = List(TestUser, TestAgent)
      )
      Orchestrator.process(TestSigil, new StubProvider, request).toList.map { _ =>
        // Give the fire-and-forget fiber a moment to land.
        Thread.sleep(250)
        val captured = recorder.captured.get()
        captured should not be empty
        captured.get._1 shouldBe userText
        captured.get._2 shouldBe "Hello world"
      }
    }
  }
}
