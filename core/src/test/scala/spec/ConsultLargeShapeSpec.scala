package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.{
  CallId, GenerationSettings, MessageContent, OutputTokenCap, Provider, ProviderCall, ProviderEvent, ProviderMessage, ProviderType,
  StopReason
}
import sigil.tool.consult.{ConsultTool, SummarizationInput, SummarizationTool}

import java.util.concurrent.atomic.AtomicReference

/**
 * Pins the `ConsultTool.invoke` seam for large-shape structured calls:
 * a multi-thousand-line user prompt reaches the provider intact (no
 * truncation on the way in), a large `OutputTokenCap.Below` cap reaches
 * the wire, and a large typed result round-trips unchanged. Consumers
 * sizing whole-artifact rewrites (whole-file refactors) depend on this
 * shape.
 */
class ConsultLargeShapeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "large-shape-model")

  // Registered with a context window large enough that the pre-flight
  // gate never sheds or rejects the multi-thousand-line prompt.
  TestSigil.cache.merge(List(Model(
    canonicalSlug = "test/large-shape-model",
    huggingFaceId = "",
    name = "large-shape-model",
    description = "",
    contextLength = 10_000_000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(10_000_000L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  ))).sync()

  /**
   * Captures every ProviderCall and answers `summarize_conversation`
   * with a canned large [[SummarizationInput]].
   */
  private class CapturingStubProvider(summary: String) extends Provider {
    val seenCalls = new AtomicReference[Vector[ProviderCall]](Vector.empty)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new UnsupportedOperationException("CapturingStubProvider"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      seenCalls.updateAndGet(_ :+ input)
      val toolName = input.tools.headOption.map(_.schema.name.value).getOrElse("")
      toolName match {
        case "summarize_conversation" =>
          val callId = CallId("large-shape")
          Stream.emits(List(
            ProviderEvent.ToolCallStart(callId, toolName),
            ProviderEvent.ToolCallComplete(callId, SummarizationInput(summary, tokenEstimate = summary.length / 4)),
            ProviderEvent.Done(StopReason.ToolCall)
          ))
        case other =>
          Stream.emits(List(ProviderEvent.Error(s"unexpected tool: $other")))
      }
    }
  }

  private def userText(call: ProviderCall): String =
    call.messages.collect { case ProviderMessage.User(blocks) =>
      blocks.collect { case MessageContent.Text(t) => t }.mkString
    }.mkString

  "ConsultTool.invoke" should {

    "deliver a multi-thousand-line prompt intact and round-trip a large typed result" in {
      TestSigil.reset()
      val bigPrompt = (1 to 4000).map(i => s"line $i: fn body content with some width to it — segment $i").mkString("\n")
      val bigSummary = (1 to 2000).map(i => s"rewritten line $i of the artifact").mkString("\n")
      val provider = new CapturingStubProvider(bigSummary)
      TestSigil.setProvider(Task.pure(provider))

      ConsultTool.invoke[SummarizationInput](
        sigil = TestSigil,
        modelId = modelId,
        chain = List(TestUser, TestAgent),
        systemPrompt = "Rewrite the artifact in full.",
        userPrompt = bigPrompt,
        tool = SummarizationTool,
        generationSettings = GenerationSettings(outputTokenCap = OutputTokenCap.Below(120_000))
      ).map { result =>
        val calls = provider.seenCalls.get()
        calls should have size 1
        val call = calls.head
        val delivered = userText(call)
        delivered.length shouldBe bigPrompt.length
        delivered shouldBe bigPrompt
        call.system shouldBe "Rewrite the artifact in full."
        call.generationSettings.explicitWireMaxTokens shouldBe Some(120_000)
        result.map(_.summary) shouldBe Some(bigSummary)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
