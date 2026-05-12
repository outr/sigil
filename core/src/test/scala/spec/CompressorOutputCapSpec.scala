package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ContextFrame, Conversation}
import sigil.conversation.compression.{MemoryContextCompressor, SummaryOnlyCompressor}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.provider.{CallId, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.tool.consult.{ExtractMemoriesInput, ExtractedMemory, SummarizationInput}

import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for sigil bug #148 — `summarize` / `extractAndPersist`
 * issued ConsultTool calls without `maxOutputTokens`, so the
 * server-side default applied. Observed in the wild generating
 * 12K-token "summaries" that got truncated mid-sentence and
 * persisted as `ContextSummary` records anyway.
 *
 * Fix: explicit `maxSummaryTokens` / `maxExtractionTokens` knobs on
 * `MemoryContextCompressor` (and `maxSummaryTokens` on
 * `SummaryOnlyCompressor`), defaults 2048 / 1024. Both compressors
 * now pass `GenerationSettings(maxOutputTokens = Some(...))` to
 * `ConsultTool.invoke`.
 *
 * Verifies the cap reaches the wire by intercepting the
 * `ProviderCall.generationSettings.maxOutputTokens` field on a
 * stub provider.
 */
class CompressorOutputCapSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "output-cap-model")

  TestSigil.cache.replace(List(Model(
    canonicalSlug       = "test/output-cap-model",
    huggingFaceId       = "",
    name                = "output-cap-model",
    description         = "",
    contextLength       = 100_000L,
    architecture        = ModelArchitecture(
      modality         = "text->text",
      inputModalities  = List("text"),
      outputModalities = List("text"),
      tokenizer        = "None",
      instructType     = None
    ),
    pricing             = ModelPricing(
      prompt = BigDecimal(0), completion = BigDecimal(0),
      webSearch = None, inputCacheRead = None
    ),
    topProvider         = ModelTopProvider(
      contextLength = Some(100_000L), maxCompletionTokens = None, isModerated = false
    ),
    perRequestLimits    = None,
    supportedParameters = Set.empty,
    knowledgeCutoff     = None,
    expirationDate      = None,
    links               = ModelLinks(details = ""),
    created             = Timestamp(),
    _id                 = modelId
  ))).sync()

  private def textFrame(s: String, id: String): ContextFrame.Text =
    ContextFrame.Text(s, TestUser, Id[Event](id))

  /** Captures the `maxOutputTokens` from every ProviderCall it sees
    * so the spec can assert the cap reached the wire. Returns
    * canned summary / extraction results so the compressor settles. */
  private class CapturingStubProvider(facts: List[String], summary: String)
    extends Provider {
    val seenMaxOutput = new AtomicReference[Vector[Option[Int]]](Vector.empty)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new UnsupportedOperationException("CapturingStubProvider"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      seenMaxOutput.updateAndGet(_ :+ input.generationSettings.maxOutputTokens)
      val toolName = input.tools.headOption.map(_.schema.name.value).getOrElse("")
      toolName match {
        case "extract_memories" =>
          val callId = CallId("extract")
          val memories = facts.map(f => ExtractedMemory(content = f, label = ""))
          Stream.emits(List(
            ProviderEvent.ToolCallStart(callId, toolName),
            ProviderEvent.ToolCallComplete(callId, ExtractMemoriesInput(memories)),
            ProviderEvent.Done(StopReason.ToolCall)
          ))
        case "summarize_conversation" =>
          val callId = CallId("summarize")
          Stream.emits(List(
            ProviderEvent.ToolCallStart(callId, toolName),
            ProviderEvent.ToolCallComplete(callId, SummarizationInput(summary, tokenEstimate = 10)),
            ProviderEvent.Done(StopReason.ToolCall)
          ))
        case other =>
          Stream.emits(List(ProviderEvent.Error(s"unexpected tool: $other")))
      }
    }
  }

  "MemoryContextCompressor" should {

    "pass maxOutputTokens to both the extraction and summarization calls" in {
      TestSigil.reset()
      val convId = Conversation.id(s"cap-${rapid.Unique()}")
      TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
        _id = convId, topics = List(TestTopicEntry)
      )))).sync()
      TestSigil.setCompressionSpace(Some(MemoryTestSpace))
      val provider = new CapturingStubProvider(
        facts   = List("Fact one for testing."),
        summary = "Test summary content."
      )
      TestSigil.setProvider(Task.pure(provider))

      val compressor = MemoryContextCompressor(
        maxSummaryTokens    = 2048,
        maxExtractionTokens = 1024
      )
      val frames = (0 until 4).toVector.map(i => textFrame(s"utterance $i", s"ev-$i"))
      for {
        _ <- compressor.compress(TestSigil, modelId, chain = List(TestUser, TestAgent), Stream.emits(frames), convId)
      } yield {
        val seen = provider.seenMaxOutput.get()
        // One extraction call + one summarisation call. Both
        // should carry the explicit cap; the only `None` would
        // be the per-iteration provider gate's preflight if any
        // — which doesn't go through this stub.
        seen should not be empty
        seen.foreach(_ should not be None)
        seen should contain(Some(1024))  // extraction
        seen should contain(Some(2048))  // summarisation
      }
    }

    "honour an explicitly-lowered cap" in {
      TestSigil.reset()
      val convId = Conversation.id(s"cap-low-${rapid.Unique()}")
      TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
        _id = convId, topics = List(TestTopicEntry)
      )))).sync()
      TestSigil.setCompressionSpace(Some(MemoryTestSpace))
      val provider = new CapturingStubProvider(
        facts   = List("Fact."),
        summary = "Sum."
      )
      TestSigil.setProvider(Task.pure(provider))

      val compressor = MemoryContextCompressor(
        maxSummaryTokens    = 256,
        maxExtractionTokens = 128
      )
      for {
        _ <- compressor.compress(TestSigil, modelId, chain = List(TestUser, TestAgent),
                                  Stream.emits(Vector(textFrame("utterance", "ev-0"))), convId)
      } yield {
        val seen = provider.seenMaxOutput.get()
        seen should contain(Some(128))
        seen should contain(Some(256))
      }
    }
  }

  "SummaryOnlyCompressor" should {

    "pass maxSummaryTokens on its summarise call" in {
      TestSigil.reset()
      val convId = Conversation.id(s"sum-cap-${rapid.Unique()}")
      TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
        _id = convId, topics = List(TestTopicEntry)
      )))).sync()
      val provider = new CapturingStubProvider(
        facts   = Nil,
        summary = "Plain summary."
      )
      TestSigil.setProvider(Task.pure(provider))

      val compressor = SummaryOnlyCompressor(maxSummaryTokens = 1500)
      val frames = (0 until 3).toVector.map(i => textFrame(s"line $i", s"e-$i"))
      for {
        _ <- compressor.compress(TestSigil, modelId, chain = List(TestUser, TestAgent), Stream.emits(frames), convId)
      } yield {
        val seen = provider.seenMaxOutput.get()
        seen should contain(Some(1500))
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
