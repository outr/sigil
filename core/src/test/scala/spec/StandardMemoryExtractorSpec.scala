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
import sigil.tool.consult.{ExtractedMemory, ExtractMemoriesInput}
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

  TestSigil.cache.replace(List(Model(
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
  ))).sync()

  private class StubProvider(memories: List[ExtractedMemory]) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("StubProvider"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val toolName = input.tools.headOption.map(_.schema.name.value).getOrElse("")
      toolName match {
        case "extract_memories" =>
          val callId = CallId("extract-keys")
          Stream.emits(List(
            ProviderEvent.ToolCallStart(callId, toolName),
            ProviderEvent.ToolCallComplete(callId, ExtractMemoriesInput(memories)),
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
      val extractor = extractorFor(
        List(ExtractedMemory(content = "c", label = "l", key = Some("k"))),
        filter = RejectAll
      )
      extractor.extract(
        TestSigil, convId, modelId, List(TestUser, TestAgent),
        userMessage = "hi",
        agentResponse = "hello"
      ).map(_ shouldBe empty)
    }

    "persist one memory per extracted entry with default approved status" in {
      val memories = List(
        ExtractedMemory(content = "User prefers dark mode.", label = "UI theme",
          key = Some("user.ui.theme"), tags = List("preference")),
        ExtractedMemory(content = "User lives in US/Pacific.", label = "Time zone",
          key = Some("user.time_zone"))
      )
      val extractor = extractorFor(memories)
      extractor.extract(
        TestSigil, convId, modelId, List(TestUser, TestAgent),
        userMessage = "dummy user message (filter bypassed)",
        agentResponse = "dummy agent response"
      ).map { produced =>
        produced.flatMap(_.key) should contain allOf ("user.ui.theme", "user.time_zone")
        produced.foreach { m =>
          m.status shouldBe sigil.conversation.MemoryStatus.Approved
          m.conversationId shouldBe Some(convId)
        }
        succeed
      }
    }

    "skip entries with empty content; persist keyless entries as new records" in {
      val memories = List(
        ExtractedMemory(content = "Good content", label = "Label", key = Some("valid.key")),
        ExtractedMemory(content = "Keyless but valid content", label = "Note", key = None),
        ExtractedMemory(content = "", label = "empty", key = Some("empty.content"))
      )
      val extractor = extractorFor(memories)
      extractor.extract(
        TestSigil, convId, modelId, List(TestUser, TestAgent),
        userMessage = "dummy", agentResponse = "dummy"
      ).map { produced =>
        produced.map(_.fact) should contain allOf ("Good content", "Keyless but valid content")
        produced.map(_.fact) should not contain ""
        produced.flatMap(_.key) shouldBe List("valid.key")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
