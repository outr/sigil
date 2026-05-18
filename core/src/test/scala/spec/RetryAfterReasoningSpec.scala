package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.Model
import sigil.provider.{
  CallId, GenerationSettings, OneShotRequest, Provider, ProviderCall,
  ProviderErrorMetadata, ProviderEvent, ProviderStreamException,
  ProviderType, StopReason
}
import sigil.tool.model.NoResponseInput
import spice.http.HttpRequest

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/**
 * Reasoning is transient — a stream that fails after only emitting
 * `ThinkingDelta` (OpenAI-compat reasoning models like Kimi via
 * OpenRouter) or `ReasoningItem` (OpenAI Responses API) should still
 * be retryable. The framework's transient-retry wrapper guards
 * against retrying after _committed_ work (text, tool calls,
 * generated images, response-state captures), but reasoning content
 * doesn't qualify: the consumer renders it as a "thinking..."
 * placeholder, and a failed attempt's buffered events are dropped
 * entirely when retry fires (only the final attempt's stream
 * reaches the orchestrator), so a fresh reasoning chain on retry
 * is invisible / desirable rather than duplicate.
 *
 * Four behaviors compose:
 *
 *   1. Reasoning-then-failure retries cleanly and the retry's full
 *      response reaches the consumer.
 *   2. Text-content-then-failure still blocks retry (the partial
 *      output would otherwise duplicate).
 *   3. Tool-call-then-failure still blocks retry.
 *   4. The retry's RetryContext still carries the prior upstream
 *      name so OpenRouter-style rotation continues to work even
 *      when the first attempt produced reasoning.
 */
class RetryAfterReasoningSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "reasoning-model")

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

  private def upstreamSilent(upstream: String): Throwable =
    new ProviderStreamException(
      providerKey   = "openrouter",
      code          = 0,
      typ           = "upstream_silent",
      message_      = "OpenRouter emitted only keepalive chunks past the threshold — upstream is unresponsive.",
      status        = None,
      errorMetadata = Some(ProviderErrorMetadata(
        errorType        = Some("upstream_silent"),
        upstreamProvider = Some(upstream)
      ))
    )

  private def successStream: Stream[ProviderEvent] =
    Stream.emits(List(
      ProviderEvent.TextDelta("the answer is 42"),
      ProviderEvent.Done(StopReason.Complete)
    ))

  private def reasoningThenSilent(upstream: String): Stream[ProviderEvent] =
    Stream.emits[ProviderEvent](List(
      ProviderEvent.ThinkingDelta("Let me think about this... ")
    )) ++ Stream.emit(()).evalMap[ProviderEvent](_ => Task.error(upstreamSilent(upstream)))

  private def reasoningTextThenSilent(upstream: String): Stream[ProviderEvent] =
    Stream.emits[ProviderEvent](List(
      ProviderEvent.ThinkingDelta("thinking..."),
      ProviderEvent.TextDelta("partial answer: ")
    )) ++ Stream.emit(()).evalMap[ProviderEvent](_ => Task.error(upstreamSilent(upstream)))

  private def reasoningToolCallThenSilent(upstream: String): Stream[ProviderEvent] =
    Stream.emits[ProviderEvent](List(
      ProviderEvent.ThinkingDelta("thinking..."),
      ProviderEvent.ToolCallStart(CallId("call_abc"), "no_response"),
      ProviderEvent.ToolCallComplete(CallId("call_abc"), NoResponseInput())
    )) ++ Stream.emit(()).evalMap[ProviderEvent](_ => Task.error(upstreamSilent(upstream)))

  private def reasoningItemThenSilent(upstream: String): Stream[ProviderEvent] =
    Stream.emits[ProviderEvent](List(
      ProviderEvent.ReasoningItem("rs_test", List("intermediate reasoning"), Some("opaque-blob"))
    )) ++ Stream.emit(()).evalMap[ProviderEvent](_ => Task.error(upstreamSilent(upstream)))

  "RetryAfterReasoningSpec" should {

    "retry when the first attempt fails after only ThinkingDelta" in {
      val provider = new StubProvider(attempt =>
        if (attempt == 1) reasoningThenSilent("Chutes") else successStream
      )
      provider(oneShot).toList.map { events =>
        provider.attemptCount.get() shouldBe 2
        events.collect { case t: ProviderEvent.TextDelta => t.text } should contain ("the answer is 42")
        events.collect { case d: ProviderEvent.Done => d } should not be empty
      }
    }

    "retry when the first attempt fails after only ReasoningItem" in {
      val provider = new StubProvider(attempt =>
        if (attempt == 1) reasoningItemThenSilent("Chutes") else successStream
      )
      provider(oneShot).toList.map { events =>
        provider.attemptCount.get() shouldBe 2
        events.collect { case t: ProviderEvent.TextDelta => t.text } should contain ("the answer is 42")
      }
    }

    "skip retry when a TextDelta was already buffered before the failure" in {
      val provider = new StubProvider(_ => reasoningTextThenSilent("Chutes"))
      provider(oneShot).toList.attempt.map { result =>
        provider.attemptCount.get() shouldBe 1
        result.isFailure shouldBe true
        result.failed.get shouldBe a [ProviderStreamException]
      }
    }

    "skip retry when a tool call was already buffered before the failure" in {
      val provider = new StubProvider(_ => reasoningToolCallThenSilent("Chutes"))
      provider(oneShot).toList.attempt.map { result =>
        provider.attemptCount.get() shouldBe 1
        result.isFailure shouldBe true
        result.failed.get shouldBe a [ProviderStreamException]
      }
    }

    "still thread the failed upstream into the retry's RetryContext after reasoning" in {
      val provider = new StubProvider(attempt =>
        if (attempt == 1) reasoningThenSilent("Chutes") else successStream
      )
      provider(oneShot).toList.map { _ =>
        val calls = provider.observedCalls.get()
        calls should have size 2
        calls.head.retryContext shouldBe None
        calls(1).retryContext.flatMap(_.lastErrorUpstreamProvider) shouldBe Some("Chutes")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
