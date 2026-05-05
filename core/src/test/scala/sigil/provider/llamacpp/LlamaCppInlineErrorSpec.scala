package sigil.provider.llamacpp

import fabric.{Json, Null, arr, num, obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.{ProviderStreamException, ToolCallAccumulator}

/**
 * Coverage for Bug #8 — when `/v1/chat/completions` returns HTTP 200
 * but embeds an `error` event mid-stream, `parseChunk` raises a
 * [[ProviderStreamException]] so `runAgentLoop`'s handler (Bug #6)
 * can surface a user-visible Failure Message instead of dropping the
 * chunk silently.
 *
 * Lives in `sigil.provider.llamacpp` so it can call the
 * package-private `parseLine` / `StreamState` directly — no
 * reflection, no spinning up a stub HTTP server.
 */
class LlamaCppInlineErrorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  spec.TestSigil.initFor(getClass.getSimpleName)

  private def provider: LlamaCppProvider =
    LlamaCppProvider(spec.TestSigil.llamaCppHost, Nil, spec.TestSigil)

  /** Build a fresh state + parse one SSE data line carrying `json`. */
  private def parse(p: LlamaCppProvider, json: Json): Vector[sigil.provider.ProviderEvent] = {
    val state = new p.StreamState(new ToolCallAccumulator(Vector.empty))
    p.parseLine("data: " + fabric.io.JsonFormatter.Compact(json), state)
  }

  "LlamaCppProvider.parseChunk (Bug #8)" should {
    "throw ProviderStreamException when an inline `error` event arrives mid-stream" in {
      val errorEvent = obj(
        "error" -> obj(
          "code"    -> num(500),
          "message" -> str("Failed to parse input at pos 28: <|tool_call>foo<tool_call|>"),
          "type"    -> str("server_error")
        )
      )
      val thrown = intercept[ProviderStreamException] {
        parse(provider, errorEvent)
      }
      thrown.code shouldBe 500
      thrown.typ shouldBe "server_error"
      thrown.message_ should include("Failed to parse input")
      thrown.providerKey shouldBe "llamacpp"
      // Outer message is the user-facing string Bug #6 plumbs into
      // ResponseContent.Failure.reason.
      thrown.getMessage should include("llamacpp returned server_error (500)")
    }

    "ignore an `error` field that is JSON null" in {
      val nullErrorEvent = obj(
        "error"   -> Null,
        "choices" -> arr()
      )
      noException should be thrownBy parse(provider, nullErrorEvent)
      rapid.Task.pure(succeed)
    }

    "still parse a normal content chunk that has no `error` key" in {
      val normalChunk = obj(
        "choices" -> arr(
          obj(
            "delta" -> obj("content" -> str("hello")),
            "index" -> num(0)
          )
        )
      )
      val events = parse(provider, normalChunk)
      events.collect { case sigil.provider.ProviderEvent.TextDelta(t) => t }.mkString shouldBe "hello"
    }
  }

  "tear down" should {
    "dispose TestSigil" in spec.TestSigil.shutdown.map(_ => succeed)
  }
}
