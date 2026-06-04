package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.{
  ConversationMode, GenerationSettings, MessageContent, OneShotRequest, Provider,
  ProviderCall, ProviderEvent, ProviderMessage, ProviderRequest, ProviderType,
  RequestOverBudgetException, ToolChoice
}
import spice.http.HttpRequest

/**
 * Sigil #301 — `preFlightGate` must apply a safety margin to
 * `Model.contextLength` (mirror of `rateLimitSafetyMargin` on the
 * rate-limit axis). The estimator's documented 7-15% piecewise-vs-
 * wire-rendered gap meant requests estimating just under
 * `Model.contextLength` were let through the gate and landed at
 * Anthropic's HTTP 400 "prompt is too long" path.
 *
 * The fix: `Sigil.contextLengthSafetyMargin: Double = 0.92`, applied
 * identically to the rate side. A request estimating between
 * `contextLength * margin` and `contextLength` triggers emergency
 * shed (or `RequestOverBudgetException`) instead of leaking to the
 * wire.
 */
class PreFlightContextMarginSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Token estimator that counts 'x' characters across system + messages.
   * Keeps the test's expected token math obvious — no jtokkit
   * variance to reason about.
   */
  private class CountingProvider(modelRecord: Model) extends Provider {
    override def `type`: ProviderType = ProviderType.OpenAI
    override def models: List[Model] = List(modelRecord)
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.empty
    override protected def estimateRequest(call: ProviderCall): Int =
      call.system.count(_ == 'x') + call.messages.foldLeft(0) { (acc, m) =>
        acc +
          (m match {
            case ProviderMessage.User(blocks) =>
              blocks.iterator.collect { case t: MessageContent.Text => t.text.count(_ == 'x') }.sum
            case ProviderMessage.Assistant(c, _) => c.count(_ == 'x')
            case ProviderMessage.ToolResult(_, c) => c.count(_ == 'x')
            case ProviderMessage.System(c) => c.count(_ == 'x')
            case _ => 0
          })
      }

    def runGate(req: ProviderRequest, call: ProviderCall): Either[Throwable, ProviderCall] = {
      val m = classOf[Provider].getDeclaredMethod(
        "preFlightGate",
        classOf[ProviderRequest],
        classOf[ProviderCall]
      )
      m.setAccessible(true)
      m.invoke(this, req, call).asInstanceOf[Either[Throwable, ProviderCall]]
    }
  }

  private def model(modelId: Id[Model], contextLength: Long): Model = Model(
    canonicalSlug = modelId.value,
    huggingFaceId = "",
    name = modelId.value,
    description = "",
    contextLength = contextLength,
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
      inputCacheRead = None),
    topProvider = ModelTopProvider(
      contextLength = Some(contextLength),
      maxCompletionTokens = None,
      isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = lightdb.time.Timestamp(),
    _id = modelId
  )

  private def callOf(m: Model,
                     system: String,
                     messages: Vector[ProviderMessage]): ProviderCall =
    ProviderCall(
      model = m,
      system = system,
      messages = messages,
      tools = Vector.empty,
      builtInTools = Set.empty,
      toolChoice = ToolChoice.None,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      currentMode = ConversationMode
    )

  private def oneShot(m: Model): ProviderRequest =
    OneShotRequest(model = m, systemPrompt = "", userPrompt = "ping")

  "Provider.preFlightGate context-length margin (sigil #301)" should {

    "shed a request that fits the raw contextLength but exceeds the margin-tightened ceiling" in {
      val modelId = Model.id("test", "ctx-margin-shed")
      // contextLength = 1000. With default margin 0.92 the effective
      // ceiling is 920. A 950-token request fits the raw limit but
      // overflows the margin — must be shed.
      val m = model(modelId, contextLength = 1000L)
      TestSigil.cache.merge(List(m)).sync()
      val provider = new CountingProvider(m)
      val msgs = (1 to 19).map(_ => ProviderMessage.User("x" * 50)).toVector // 950 tokens of messages
      val call = callOf(m, system = "", messages = msgs)
      val result = provider.runGate(oneShot(m), call)
      Task {
        result.isRight shouldBe true
        val shed = result.toOption.get
        // Estimator must report the shed call under the
        // margin-tightened ceiling.
        provider.runGate(oneShot(m), shed).isRight shouldBe true
        shed.messages.size should be < msgs.size
      }
    }

    "throw RequestOverBudgetException when even the un-sheddable system prompt exceeds the margin ceiling" in {
      val modelId = Model.id("test", "ctx-margin-overbudget")
      // contextLength = 1000, margin 0.92 → ceiling 920.
      // System-prompt of 950 tokens overflows the margin AND can't be
      // shed (critical memories live in `system`). Pre-fix this would
      // have passed because 950 ≤ 1000.
      val m = model(modelId, contextLength = 1000L)
      TestSigil.cache.merge(List(m)).sync()
      val provider = new CountingProvider(m)
      val call = callOf(m, system = "x" * 950, messages = Vector(ProviderMessage.User("x" * 10)))
      val result = provider.runGate(oneShot(m), call)
      Task {
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[RequestOverBudgetException]
      }
    }

    "let a request that fits the margin-tightened ceiling pass through unchanged" in {
      val modelId = Model.id("test", "ctx-margin-fits")
      // contextLength = 1000, margin 0.92 → ceiling 920. A 500-token
      // request fits comfortably — must pass through without shedding.
      val m = model(modelId, contextLength = 1000L)
      TestSigil.cache.merge(List(m)).sync()
      val provider = new CountingProvider(m)
      val call = callOf(m, system = "x" * 200, messages = Vector(ProviderMessage.User("x" * 300)))
      val result = provider.runGate(oneShot(m), call)
      Task {
        result.isRight shouldBe true
        result.toOption.get.messages.size shouldBe 1
        result.toOption.get.system.length shouldBe 200
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
