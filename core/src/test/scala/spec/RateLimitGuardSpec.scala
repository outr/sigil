package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.Model
import sigil.provider.{
  ConversationMode, ErrorClassification, ErrorClassifier, GenerationSettings,
  MessageContent, Provider, ProviderCall, ProviderEvent, ProviderMessage,
  ProviderRequest, ProviderType, RequestExceedsRateLimitException, RequestOverBudgetException
}
import spice.http.HttpRequest

/**
 * Regression for sigil bug #283 — pre-flight rate-limit guard refuses
 * to send a single request whose estimated input-token count exceeds
 * the model's `inputTokensPerMinute * Sigil.rateLimitSafetyMargin`
 * ceiling, and the resulting `RequestExceedsRateLimitException` is
 * classified Fatal so the framework's 429-retry path doesn't burn the
 * minute's budget on a request that provably can't succeed.
 */
class RateLimitGuardSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Counts 'x' characters across all message content blocks — keeps
    * the test's expected token math obvious. */
  private class CountingProvider(modelRecord: Model) extends Provider {
    override def `type`: ProviderType = ProviderType.OpenAI
    override def models: List[Model] = List(modelRecord)
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.empty
    override protected def estimateRequest(call: ProviderCall): Int = {
      call.system.count(_ == 'x') + call.messages.foldLeft(0) { (acc, m) =>
        acc + (m match {
          case ProviderMessage.User(blocks) =>
            blocks.iterator.collect { case t: MessageContent.Text => t.text.count(_ == 'x') }.sum
          case ProviderMessage.Assistant(c, _)  => c.count(_ == 'x')
          case ProviderMessage.ToolResult(_, c) => c.count(_ == 'x')
          case ProviderMessage.System(c)        => c.count(_ == 'x')
          case _                                => 0
        })
      }
    }

    /** Reflectively drive the private pre-flight gate. */
    def runGate(req: ProviderRequest, call: ProviderCall): Either[Throwable, ProviderCall] = {
      val m = classOf[Provider].getDeclaredMethod(
        "preFlightGate", classOf[ProviderRequest], classOf[ProviderCall]
      )
      m.setAccessible(true)
      m.invoke(this, req, call).asInstanceOf[Either[Throwable, ProviderCall]]
    }
  }

  private def baseModel(modelId: lightdb.id.Id[Model],
                        contextLength: Long,
                        ratePerMinute: Option[Long]): Model = Model(
    canonicalSlug                  = modelId.value,
    huggingFaceId                  = "",
    name                           = modelId.value,
    description                    = "",
    contextLength                  = contextLength,
    architecture                   = sigil.db.ModelArchitecture(
      modality         = "text->text",
      inputModalities  = List("text"),
      outputModalities = List("text"),
      tokenizer        = "None",
      instructType     = None
    ),
    pricing                        = sigil.db.ModelPricing(
      prompt    = BigDecimal(0),
      completion = BigDecimal(0),
      webSearch = None,
      inputCacheRead = None
    ),
    topProvider                    = sigil.db.ModelTopProvider(
      contextLength       = Some(contextLength),
      maxCompletionTokens = None,
      isModerated         = false
    ),
    perRequestLimits               = None,
    supportedParameters            = Set.empty,
    knowledgeCutoff                = None,
    expirationDate                 = None,
    links                          = sigil.db.ModelLinks(details = ""),
    created                        = lightdb.time.Timestamp(),
    inputTokensPerMinute           = ratePerMinute,
    _id                            = modelId
  )

  private def callOf(model: Model,
                     messages: Vector[ProviderMessage],
                     tools: Vector[sigil.tool.Tool] = Vector.empty,
                     system: String = ""): ProviderCall =
    ProviderCall(
      model              = model,
      system             = system,
      messages           = messages,
      tools              = tools,
      builtInTools       = Set.empty,
      toolChoice         = sigil.provider.ToolChoice.None,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      currentMode        = ConversationMode
    )

  private def oneShot(modelId: lightdb.id.Id[Model]): ProviderRequest =
    sigil.provider.OneShotRequest(
      model        = TestSigil.testModel(modelId),
      systemPrompt = "",
      userPrompt   = "ping"
    )

  "Provider pre-flight gate (sigil #283)" should {

    "let a request that fits the rate-limit ceiling pass through unchanged" in {
      val modelId = Model.id("test", "rl-fits")
      // 1000 tokens/minute × 0.85 = 850-token ceiling. Request is 100 tokens.
      val model = baseModel(modelId, contextLength = 10_000L, ratePerMinute = Some(1000L))
      TestSigil.cache.merge(List(model)).sync()
      val provider = new CountingProvider(model)
      val call = callOf(model, Vector(ProviderMessage.User("x" * 100)))
      val result = provider.runGate(oneShot(modelId), call)
      Task {
        result.isRight shouldBe true
        result.toOption.get.messages.size shouldBe 1
      }
    }

    "trigger emergency shed when the request exceeds the rate ceiling but can be trimmed under" in {
      val modelId = Model.id("test", "rl-shed")
      // 1000 tokens/minute × 0.85 = 850-token ceiling. 20 messages × 100 tokens = 2000 total.
      val model = baseModel(modelId, contextLength = 100_000L, ratePerMinute = Some(1000L))
      TestSigil.cache.merge(List(model)).sync()
      val provider = new CountingProvider(model)
      val msgs = (1 to 20).map(_ => ProviderMessage.User("x" * 100)).toVector
      val call = callOf(model, msgs)
      val result = provider.runGate(oneShot(modelId), call)
      Task {
        result.isRight shouldBe true
        val shed = result.toOption.get
        shed.messages.size should be < msgs.size
        provider.runGate(oneShot(modelId), shed).isRight shouldBe true
      }
    }

    "throw RequestExceedsRateLimitException when even the un-sheddable core exceeds the rate ceiling" in {
      val modelId = Model.id("test", "rl-too-big")
      // 1000 tokens/min × 0.85 = 850-token ceiling. System-prompt
      // contribution alone is 5000 tokens — the framework's emergency
      // shed CAN'T touch the system prompt (critical memories live
      // there), so this request can never fit and the guard must
      // raise a structured exception instead of silently sending a
      // doomed request into the 429 retry loop.
      val model = baseModel(modelId, contextLength = 100_000L, ratePerMinute = Some(1000L))
      TestSigil.cache.merge(List(model)).sync()
      val provider = new CountingProvider(model)
      val call = callOf(
        model,
        messages = Vector(ProviderMessage.User("x" * 200)),
        system   = "x" * 5000
      )
      val result = provider.runGate(oneShot(modelId), call)
      Task {
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a [RequestExceedsRateLimitException]
        val ex = result.swap.toOption.get.asInstanceOf[RequestExceedsRateLimitException]
        ex.inputTokensPerMinute shouldBe 1000L
        ex.modelId shouldBe modelId
      }
    }

    "skip the rate-limit guard entirely when inputTokensPerMinute is unset" in {
      val modelId = Model.id("test", "rl-unset")
      // Context fits (10K). No rate limit → no guardrail trip on a giant request.
      val model = baseModel(modelId, contextLength = 1_000_000L, ratePerMinute = None)
      TestSigil.cache.merge(List(model)).sync()
      val provider = new CountingProvider(model)
      val call = callOf(model, Vector(ProviderMessage.User("x" * 500_000)))
      val result = provider.runGate(oneShot(modelId), call)
      Task {
        result.isRight shouldBe true
      }
    }

    "prefer RequestOverBudgetException when context limit is the tighter constraint" in {
      val modelId = Model.id("test", "ctx-tighter")
      // contextLength = 100 (tight). rate = 1M tokens/min × 0.85 = 850K.
      // System-prompt of 5000 tokens fundamentally overflows the 100-token
      // context window, so the residual exceeds contextLength after shed
      // and the exception picks the contextLength side.
      val model = baseModel(modelId, contextLength = 100L, ratePerMinute = Some(1_000_000L))
      TestSigil.cache.merge(List(model)).sync()
      val provider = new CountingProvider(model)
      val call = callOf(
        model,
        messages = Vector(ProviderMessage.User("x" * 50)),
        system   = "x" * 5000
      )
      val result = provider.runGate(oneShot(modelId), call)
      Task {
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a [RequestOverBudgetException]
      }
    }
  }

  "ErrorClassifier.Default (sigil #283)" should {

    "classify RequestExceedsRateLimitException as Fatal so it doesn't enter the 429 retry loop" in Task {
      val ex = new RequestExceedsRateLimitException(
        estimatedTokens      = 5000,
        inputTokensPerMinute = 1000L,
        safetyMargin         = 0.85,
        modelId              = Model.id("test", "rl-classifier")
      )
      ErrorClassifier.Default.classify(ex) shouldBe ErrorClassification.Fatal
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
