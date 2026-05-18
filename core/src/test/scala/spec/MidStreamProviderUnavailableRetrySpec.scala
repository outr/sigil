package spec

import fabric.*
import fabric.io.JsonParser
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.Model
import sigil.provider.{
  ErrorClassification, ErrorClassifier, GenerationSettings,
  OneShotRequest, Provider, ProviderCall, ProviderErrorMetadata,
  ProviderEvent, ProviderStreamException, ProviderType, RetryContext, StopReason, ToolCallAccumulator
}
import sigil.provider.openrouter.{OpenRouter, OpenRouterProvider, OpenRouterProviderRouting}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.provider.wire.OpenAIChatCompletions.{Config, StreamState}
import spice.http.HttpRequest

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

/**
 * Coverage for the mid-stream `provider_unavailable` recovery flow.
 * Four behaviors compose:
 *
 *   1. Mid-stream 502 carrying `provider_unavailable` metadata
 *      classifies as transient — the framework's transient-retry
 *      wrapper fires a fresh attempt that succeeds.
 *   2. The retry threads the prior failure's upstream-provider name
 *      into [[OpenRouterProvider]]'s `provider.ignore` deny-list so
 *      OpenRouter routes the retry around the sick upstream.
 *   3. Pure keepalive silence — the SSE stream emits only comment
 *      heartbeats for longer than the configured budget — raises a
 *      typed `upstream_silent` exception the classifier treats as
 *      retryable.
 *   4. Non-transient failures (401 unauthorized) propagate without
 *      retry.
 */
class MidStreamProviderUnavailableRetrySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "kimi-k2.5-0127")

  /** Stub provider whose per-call behaviour is a per-attempt
    * function — drives the framework's transient-retry wrapper
    * through `Provider.apply`. Captures every per-attempt
    * `ProviderCall` so tests can assert what `retryContext`
    * carried. */
  private class StubProvider(perAttempt: Int => Stream[ProviderEvent]) extends Provider {
    val attemptCount: AtomicInteger = new AtomicInteger(0)
    val observedCalls: AtomicReference[List[ProviderCall]] =
      new AtomicReference(List.empty)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("stub provider — no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = attemptCount.incrementAndGet()
      observedCalls.updateAndGet(calls => calls :+ input)
      perAttempt(n)
    }
  }

  private def oneShot: OneShotRequest = OneShotRequest(
    modelId            = modelId,
    systemPrompt       = "test-system",
    userPrompt         = "test-user",
    generationSettings = GenerationSettings()
  )

  private def successStream: Stream[ProviderEvent] =
    Stream.emits(List(
      ProviderEvent.TextDelta("hello"),
      ProviderEvent.Done(StopReason.Complete)
    ))

  /** Errors with a typed `ProviderStreamException` whose metadata
    * names the failed upstream. Carries `errorType =
    * provider_unavailable` so the default classifier promotes it
    * to `Retry`. */
  private def midStream502(upstream: String): Stream[ProviderEvent] =
    Stream.emit(()).evalMap[ProviderEvent] { _ =>
      Task.error(new ProviderStreamException(
        providerKey   = "openrouter",
        code          = 502,
        typ           = "error",
        message_      = "Upstream idle timeout exceeded",
        status        = Some(502),
        errorMetadata = Some(ProviderErrorMetadata(
          errorType        = Some("provider_unavailable"),
          upstreamProvider = Some(upstream)
        ))
      ))
    }

  /** Errors with a plain HTTP-401 RuntimeException — the default
    * classifier marks this `Fatal` so no retry fires. */
  private def auth401: Stream[ProviderEvent] =
    Stream.emit(()).evalMap[ProviderEvent] { _ =>
      Task.error(new RuntimeException("HTTP 401 Unauthorized — bad api key"))
    }

  "MidStreamProviderUnavailableRetrySpec" should {

    "retry once when the first attempt fails with provider_unavailable mid-stream" in {
      val provider = new StubProvider(attempt =>
        if (attempt == 1) midStream502("Chutes")
        else successStream
      )
      provider(oneShot).toList.map { events =>
        provider.attemptCount.get() shouldBe 2
        events.collect { case t: ProviderEvent.TextDelta => t.text } should contain ("hello")
        events.collect { case d: ProviderEvent.Done => d } should not be empty
      }
    }

    "thread the failed upstream into RetryContext on the retry attempt" in {
      val provider = new StubProvider(attempt =>
        if (attempt == 1) midStream502("Chutes")
        else successStream
      )
      provider(oneShot).toList.map { _ =>
        val calls = provider.observedCalls.get()
        calls should have size 2
        calls.head.retryContext shouldBe None
        calls(1).retryContext shouldBe Some(RetryContext(lastErrorUpstreamProvider = Some("Chutes")))
      }
    }

    "OpenRouterProvider appends the failed upstream to provider.ignore on the retry" in {
      // Render the wire body twice — once with no retry context, once
      // with the prior failure's upstream-provider name — and inspect
      // the `provider.ignore` array each time. The framework's
      // `httpRequestFor` path runs the same body-builder the live
      // streaming call would; no need to spin up an HTTP server.
      val routing = OpenRouterProviderRouting(ignore = Some(List("baseline-deny")))
      val provider = OpenRouterProvider(apiKey = "test-key", sigilRef = TestSigil, providerRouting = routing)
      val firstAttempt = ProviderCall(
        modelId            = modelId,
        system             = "s",
        messages           = Vector.empty,
        tools              = Vector.empty,
        builtInTools       = Set.empty,
        toolChoice         = _root_.sigil.provider.ToolChoice.None,
        generationSettings = GenerationSettings()
      )
      val retryAttempt = firstAttempt.copy(retryContext = Some(
        RetryContext(lastErrorUpstreamProvider = Some("Chutes"))
      ))
      for {
        firstReq <- provider.httpRequestFor(firstAttempt)
        retryReq <- provider.httpRequestFor(retryAttempt)
      } yield {
        val firstBody = bodyJson(firstReq)
        val retryBody = bodyJson(retryReq)
        val firstIgnore = ignoreList(firstBody)
        val retryIgnore = ignoreList(retryBody)
        firstIgnore should contain only "baseline-deny"
        retryIgnore should contain ("baseline-deny")
        retryIgnore should contain ("Chutes")
      }
    }

    "keepalive silence past the configured budget raises a retryable upstream_silent exception" in {
      val now = new AtomicLong(0L)
      val state = new StreamState(
        acc                       = new ToolCallAccumulator(Vector.empty, providerKey = "openrouter"),
        nowNanos                  = () => now.get(),
        streamingSilenceTimeoutMs = 30000L
      )
      val cfg = Config(providerNamespace = OpenRouter.Provider, providerName = "OpenRouter")

      // First keepalive arms the silence anchor at t=0.
      OpenAIChatCompletions.parseLine(": OPENROUTER PROCESSING", state, cfg) shouldBe empty
      // 25s elapsed — under budget, no throw.
      now.set(25000L * 1000000L)
      OpenAIChatCompletions.parseLine(": OPENROUTER PROCESSING", state, cfg) shouldBe empty
      // 35s elapsed — over the 30s budget, the next keepalive throws.
      now.set(35000L * 1000000L)
      val ex = intercept[ProviderStreamException] {
        OpenAIChatCompletions.parseLine(": OPENROUTER PROCESSING", state, cfg)
      }
      ex.typ shouldBe "upstream_silent"
      ex.errorMetadata.flatMap(_.errorType) shouldBe Some("upstream_silent")
      ErrorClassifier.Default.classify(ex) shouldBe ErrorClassification.Retry
      Task.unit.map(_ => succeed)
    }

    "meaningful chunks bump the silence anchor so legitimate slow generations don't false-fire" in {
      val now = new AtomicLong(0L)
      val state = new StreamState(
        acc                       = new ToolCallAccumulator(Vector.empty, providerKey = "openrouter"),
        nowNanos                  = () => now.get(),
        streamingSilenceTimeoutMs = 30000L
      )
      val cfg = Config(providerNamespace = OpenRouter.Provider, providerName = "OpenRouter")

      // Anchor the stream.
      OpenAIChatCompletions.parseLine(": OPENROUTER PROCESSING", state, cfg) shouldBe empty
      // 25s of keepalives, then a real content chunk that bumps the anchor.
      now.set(25000L * 1000000L)
      OpenAIChatCompletions.parseLine(": OPENROUTER PROCESSING", state, cfg) shouldBe empty
      now.set(28000L * 1000000L)
      val contentChunk = """data: {"choices":[{"delta":{"content":"first token"}}]}"""
      val emitted = OpenAIChatCompletions.parseLine(contentChunk, state, cfg)
      emitted.exists { case _: ProviderEvent.TextDelta => true; case _ => false } shouldBe true
      // Another 25s of keepalives after the bump — still under budget, no throw.
      now.set(53000L * 1000000L)
      OpenAIChatCompletions.parseLine(": OPENROUTER PROCESSING", state, cfg) shouldBe empty
      Task.unit.map(_ => succeed)
    }

    "non-transient errors (401) propagate without retry" in {
      val provider = new StubProvider(_ => auth401)
      provider(oneShot).toList.attempt.map { result =>
        result.isFailure shouldBe true
        provider.attemptCount.get() shouldBe 1
        result.failed.get.getMessage should include ("401")
      }
    }
  }

  /** Extract the `provider.ignore` list from a rendered chat-completions body. */
  private def ignoreList(body: Json): List[String] = {
    val routing = body.get("provider").getOrElse(Obj.empty)
    routing.get("ignore").map(_.asVector.map(_.asString).toList).getOrElse(Nil)
  }

  /** Pull the JSON body off the rendered [[HttpRequest]] for assertion. */
  private def bodyJson(req: HttpRequest): Json = req.content match {
    case Some(c: spice.http.content.StringContent) => JsonParser(c.value)
    case other => fail(s"expected StringContent body, got: $other")
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
