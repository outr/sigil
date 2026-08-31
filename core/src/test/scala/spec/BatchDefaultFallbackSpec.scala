package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.Model
import sigil.provider.{
  GenerationSettings, OneShotRequest, OneShotResponse, Provider, ProviderCall,
  ProviderEvent, ProviderRequest, ProviderType, TokenUsage
}
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

/**
 * Regression for sigil #299 — `Provider.batch(Stream[OneShotRequest])`
 * default fallback. Uses a synthetic provider whose `call` emits one
 * `TextDelta` + `Usage` + `Done` per request; `batch` should drain
 * each through `apply` and yield a [[OneShotResponse]] correlated
 * back to the originating request's `requestId`.
 *
 * Covers:
 *   - Stream-in / stream-out contract: 5 requests in → 5 responses
 *     out, in input order (default sequential fallback preserves
 *     order).
 *   - `requestId` round-trip — consumers can re-pair responses to
 *     their source rows.
 *   - `content` accumulation — `TextDelta` chunks coalesce into one
 *     `ResponseContent.Text` block.
 *   - `usage` propagation when the provider emits a `Usage` event.
 *   - `batchSupported = false` by default (this provider has no
 *     native batch override).
 */
class BatchDefaultFallbackSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Synthetic provider: echo the request's `userPrompt` reversed
   * as TextDelta + a fixed Usage. Stable enough to assert response
   * content / usage match.
   */
  private class EchoProvider extends Provider {
    override def `type`: ProviderType = ProviderType.OpenAI
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val text = input.messages.collectFirst {
        case _root_.sigil.provider.ProviderMessage.User(blocks) =>
          blocks.collectFirst {
            case t: _root_.sigil.provider.MessageContent.Text => t.text
          }.getOrElse("")
      }.getOrElse("")
      Stream.emits(List(
        ProviderEvent.TextDelta(text.reverse),
        ProviderEvent.Usage(TokenUsage(promptTokens = text.length, completionTokens = text.length, totalTokens = text.length * 2)),
        ProviderEvent.Done(_root_.sigil.provider.StopReason.Complete)
      ))
    }
  }

  private val provider = new EchoProvider

  private def req(text: String): OneShotRequest = OneShotRequest(
    model = TestSigil.defaultTestModel,
    systemPrompt = "",
    userPrompt = text
  )

  "Provider.batch (sigil #299)" should {

    "default to batchSupported = false" in Task {
      provider.batchSupported shouldBe false
    }

    "stream OneShotResponses correlated by requestId in input order" in {
      val inputs = List(req("foo"), req("bar"), req("baz"))
      provider.batch(Stream.emits(inputs)).toList.map { responses =>
        responses should have size 3
        responses.map(_.requestId) shouldBe inputs.map(_.requestId)
        responses.map(_.content.collectFirst {
          case t: ResponseContent.Text => t.text
        }) shouldBe List(Some("oof"), Some("rab"), Some("zab"))
      }
    }

    "propagate Usage events into OneShotResponse.usage" in {
      val r = req("hello")
      provider.batch(Stream.emit(r)).toList.map { responses =>
        responses should have size 1
        responses.head.usage.map(_.promptTokens) shouldBe Some(5)
        responses.head.usage.map(_.completionTokens) shouldBe Some(5)
        responses.head.error shouldBe None
      }
    }

    "accept an empty input stream and emit an empty output stream" in
      provider.batch(Stream.empty).toList.map { responses =>
        responses shouldBe Nil
      }
  }

  "Default applyOneShot fallback" should {

    "surface a thrown call into OneShotResponse.error rather than aborting the stream" in {
      class FailingProvider extends Provider {
        override def `type`: ProviderType = ProviderType.OpenAI
        override protected def sigil: _root_.sigil.Sigil = TestSigil
        override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
          Task.error(new UnsupportedOperationException("no wire"))
        override def call(input: ProviderCall): Stream[ProviderEvent] =
          Stream.force(Task.error(new RuntimeException("boom")))
      }
      val failing = new FailingProvider
      val req1 = req("works1")
      val req2 = req("works2")
      failing.batch(Stream.emits(List(req1, req2))).toList.map { responses =>
        responses should have size 2
        responses.foreach { r =>
          r.error shouldBe defined
          r.error.get.message should include("boom")
        }
        responses.map(_.requestId) shouldBe List(req1.requestId, req2.requestId)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
