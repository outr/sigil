package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.conversation.compression.extract.StandardMemoryExtractor
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.{CallId, OutputTokenCap, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.tool.consult.{ExtractMemoriesInput, ExtractedMemory}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for sigil bug #303 — `StandardMemoryExtractor` previously
 * pinned the `extract_memories` consult call to
 * `max_tokens = 1500` via [[sigil.tool.consult.ExtractMemoriesTool]]'s
 * canonical `consultSettings`. On a large user paste (e.g. a multi-KB
 * sitemap.json) the model burned 1500 tokens buffering the structured
 * `tool_use` input before any `partial_json` delta streamed, so the
 * framework received a tool_use with empty input and recorded zero
 * memories silently.
 *
 * The fix:
 *   - `StandardMemoryExtractor.maxExtractionTokens: Int = 8192` is now a
 *     constructor knob (mirrors the `MemoryContextCompressor.maxExtractionTokens`
 *     hook). The extractor stamps it onto the `extract_memories`
 *     consult's `GenerationSettings.outputTokenCap`, overriding the
 *     tool's canonical default for this call site.
 *   - `ExtractMemoriesTool.consultSettings` baseline is raised from
 *     1500 to 8192 so any caller that falls through to
 *     `ConsultTool.settingsFor(ExtractMemoriesTool)` benefits.
 *
 * Verifies the cap reaches the wire by intercepting
 * `ProviderCall.generationSettings.explicitWireMaxTokens` on a stub
 * provider.
 */
class MemoryExtractionLargeInputSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "extraction-cap-model")
  TestSigil.testModel(modelId)

  TestSigil.cache.replace(List(Model(
    canonicalSlug = "test/extraction-cap-model",
    huggingFaceId = "",
    name = "extraction-cap-model",
    description = "",
    contextLength = 100_000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(
      prompt = BigDecimal(0),
      completion = BigDecimal(0),
      webSearch = None,
      inputCacheRead = None
    ),
    topProvider = ModelTopProvider(
      contextLength = Some(100_000L),
      maxCompletionTokens = Some(64_000L),
      isModerated = false
    ),
    perRequestLimits = None,
    supportedParameters = Set("temperature", "max_tokens", "top_p", "tools", "tool_choice"),
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  ))).sync()

  /**
   * Captures the `max_tokens` cap on every ProviderCall it sees so
   * the spec can assert the extractor's wire request carries the
   * expected ceiling. Returns a canned `extract_memories` result so
   * the extractor settles.
   */
  private class CapturingStubProvider extends Provider {
    val seenMaxOutput = new AtomicReference[Vector[Option[Int]]](Vector.empty)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("CapturingStubProvider"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      seenMaxOutput.updateAndGet(_ :+ input.generationSettings.explicitWireMaxTokens)
      val toolName = input.tools.headOption.map(_.schema.name.value).getOrElse("")
      toolName match {
        case "extract_memories" =>
          val callId = CallId("extract")
          val memories = List(ExtractedMemory(content = "Fact.", label = "Label"))
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

  private val convId: Id[Conversation] = Conversation.id(s"extract-cap-${rapid.Unique()}")

  // Synthetic large excerpt — large enough that an 1500-token output cap
  // is dangerously tight for the structured tool_use the extractor must
  // emit. Mirrors the Shopkeeper sitemap.json paste shape.
  private val largeUserPaste: String = (1 to 200).map { i =>
    s"""{"path":"/page-$i","title":"Page $i","navLabel":"Section $i","color":"#${i.toHexString.padTo(6, '0')}"}"""
  }.mkString("\n")

  "StandardMemoryExtractor (sigil #303)" should {

    "issue the extract_memories consult call with a cap well above the prior 1500 default" in {
      TestSigil.reset()
      TestSigil.setCompressionSpace(Some(MemoryTestSpace))
      val provider = new CapturingStubProvider
      TestSigil.setProvider(Task.pure(provider))

      val extractor = StandardMemoryExtractor(
        spaceIdFor = _ => Task.pure(Some(MemoryTestSpace))
      )

      extractor.extract(
        TestSigil,
        convId,
        modelId,
        List(TestUser, TestAgent),
        userMessage = largeUserPaste,
        agentResponse = "Acknowledged — sitemap captured."
      ).map { _ =>
        val seen = provider.seenMaxOutput.get()
        seen should not be empty
        seen.foreach { cap =>
          cap shouldBe defined
          // Must clear at least 4096 — 1500 is the pathological prior
          // and even 2048 is too tight for a rich-input extraction.
          cap.get should be >= 4096
        }
        succeed
      }
    }

    "honour an explicit maxExtractionTokens override on the constructor" in {
      TestSigil.reset()
      TestSigil.setCompressionSpace(Some(MemoryTestSpace))
      val provider = new CapturingStubProvider
      TestSigil.setProvider(Task.pure(provider))

      val extractor = StandardMemoryExtractor(
        spaceIdFor = _ => Task.pure(Some(MemoryTestSpace)),
        maxExtractionTokens = 12_000
      )

      extractor.extract(
        TestSigil,
        convId,
        modelId,
        List(TestUser, TestAgent),
        userMessage = largeUserPaste,
        agentResponse = "Acknowledged."
      ).map { _ =>
        val seen = provider.seenMaxOutput.get()
        seen should contain(Some(12_000))
      }
    }
  }

  "ExtractMemoriesTool.consultSettings (sigil #303)" should {
    "expose a default outputTokenCap above the prior 1500 ceiling" in Task.pure {
      val cap = sigil.tool.consult.ExtractMemoriesTool.consultSettings.effectiveCap
      cap match {
        case OutputTokenCap.Below(n) => n should be >= 4096
        case other => fail(s"expected Below(n>=4096), got $other")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
