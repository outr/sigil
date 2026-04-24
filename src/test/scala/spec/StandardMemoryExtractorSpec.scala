package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.conversation.compression.extract.{HighSignalFilter, StandardMemoryExtractor}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.{CallId, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.tool.consult.{ExtractedMemory, ExtractMemoriesWithKeysInput}
import spice.http.HttpRequest

/**
 * Mechanical coverage for [[StandardMemoryExtractor]] against a
 * stubbed provider. Exercises:
 *   - high-signal filter short-circuit (no LLM call when rejected)
 *   - LLM pass → keyed memories upserted via
 *     [[sigil.Sigil.upsertMemoryByKey]]
 *   - pending status landing for apps with an approval UX
 */
class StandardMemoryExtractorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "extractor-model")

  TestSigil.withDB(_.model.transaction(_.upsert(Model(
    canonicalSlug = "test/extractor-model",
    huggingFaceId = "",
    name = "Test Extractor Model",
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

  private class StubProvider(memories: List[ExtractedMemory]) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("StubProvider"))
    override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
      val toolName = input.tools.headOption.map(_.schema.name.value).getOrElse("")
      toolName match {
        case "extract_memories_with_keys" =>
          val callId = CallId("extract-keys")
          Stream.emits(List(
            ProviderEvent.ToolCallStart(callId, toolName),
            ProviderEvent.ToolCallComplete(callId, ExtractMemoriesWithKeysInput(memories)),
            ProviderEvent.Done(StopReason.ToolCall)
          ))
        case other =>
          Stream.emits(List(ProviderEvent.Error(s"unexpected tool: $other")))
      }
    }
  }

  private val AcceptAll = new HighSignalFilter { override def isHighSignal(m: String) = true }
  private val RejectAll = new HighSignalFilter { override def isHighSignal(m: String) = false }

  private def extractorFor(memories: List[ExtractedMemory],
                           filter: HighSignalFilter = AcceptAll): StandardMemoryExtractor = {
    TestSigil.reset()
    TestSigil.setProvider(Task.pure(new StubProvider(memories)))
    TestSigil.setCompressionSpace(Some(MemoryTestSpace))
    StandardMemoryExtractor(
      filter = filter,
      spaceIdFor = _ => Task.pure(Some(MemoryTestSpace))
    )
  }

  private val convId: Id[Conversation] = Conversation.id(s"extractor-${rapid.Unique()}")

  "StandardMemoryExtractor" should {
    "short-circuit with Nil when the filter rejects the message" in {
      val extractor = extractorFor(List(ExtractedMemory("k", "l", "c")), filter = RejectAll)
      extractor.extract(
        TestSigil, convId, modelId, List(TestUser, TestAgent),
        userMessage = "hi",
        agentResponse = "hello"
      ).map(_ shouldBe empty)
    }

    "persist one memory per extracted entry with pending status" in {
      val memories = List(
        ExtractedMemory("user.ui.theme", "UI theme", "User prefers dark mode.", List("preference")),
        ExtractedMemory("user.time_zone", "Time zone", "User lives in US/Pacific.")
      )
      val extractor = extractorFor(memories)
      extractor.extract(
        TestSigil, convId, modelId, List(TestUser, TestAgent),
        userMessage = "dummy user message (filter bypassed)",
        agentResponse = "dummy agent response"
      ).map { produced =>
        produced.map(_.key) should contain allOf ("user.ui.theme", "user.time_zone")
        produced.foreach { m =>
          m.status shouldBe sigil.conversation.MemoryStatus.Pending
          m.conversationId shouldBe Some(convId)
        }
        succeed
      }
    }

    "skip entries with empty key or content" in {
      val memories = List(
        ExtractedMemory("valid.key", "Label", "Good content"),
        ExtractedMemory("", "no key", "bad"),
        ExtractedMemory("empty.content", "empty", "")
      )
      val extractor = extractorFor(memories)
      extractor.extract(
        TestSigil, convId, modelId, List(TestUser, TestAgent),
        userMessage = "dummy", agentResponse = "dummy"
      ).map { produced =>
        produced.map(_.key) shouldBe List("valid.key")
      }
    }
  }
}
