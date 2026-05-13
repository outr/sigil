package sigil.provider.llamacpp

import fabric.{Json, Null, arr, num, obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.{ProviderStreamException, ToolCallAccumulator}
import sigil.provider.wire.OpenAIChatCompletions

/**
 * Coverage for Bug #8 — when `/v1/chat/completions` returns HTTP 200
 * but embeds an `error` event mid-stream, the shared chat-completions
 * wire raises a [[ProviderStreamException]] (when `inlineErrorThrows`
 * is configured) so `runAgentLoop`'s handler (Bug #6) can surface a
 * user-visible Failure Message instead of dropping the chunk silently.
 *
 * Drives the wire object's `parseLine` directly; no HTTP server.
 */
class LlamaCppInlineErrorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  spec.TestSigil.initFor(getClass.getSimpleName)

  // Use the same Config LlamaCppProvider builds: providerNamespace
  // drives the exception's reported provider key, and inlineErrorThrows
  // is the gate under test.
  private val cfg: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace    = LlamaCpp.Provider,
    providerName         = "LlamaCpp",
    nonStrictSchemaTransform = identity,
    inlineErrorThrows    = true
  )

  /** Build a fresh state + parse one SSE data line carrying `json`. */
  private def parse(json: Json): Vector[sigil.provider.ProviderEvent] = {
    val state = new OpenAIChatCompletions.StreamState(new ToolCallAccumulator(Vector.empty))
    OpenAIChatCompletions.parseLine("data: " + fabric.io.JsonFormatter.Compact(json), state, cfg)
  }

  "OpenAIChatCompletions.parseChunk (Bug #8)" should {
    "throw ProviderStreamException when an inline `error` event arrives mid-stream" in {
      val errorEvent = obj(
        "error" -> obj(
          "code"    -> num(500),
          "message" -> str("Failed to parse input at pos 28: <|tool_call>foo<tool_call|>"),
          "type"    -> str("server_error")
        )
      )
      val thrown = intercept[ProviderStreamException] {
        parse(errorEvent)
      }
      thrown.code shouldBe 500
      thrown.typ shouldBe "server_error"
      thrown.message_ should include("Failed to parse input")
      thrown.providerKey shouldBe "llamacpp"
      // Outer message is the user-facing string Bug #6 plumbs into
      // MessageDisposition.Failure.reason.
      thrown.getMessage should include("llamacpp returned server_error (500)")
    }

    "ignore an `error` field that is JSON null" in {
      val nullErrorEvent = obj(
        "error"   -> Null,
        "choices" -> arr()
      )
      noException should be thrownBy parse(nullErrorEvent)
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
      val events = parse(normalChunk)
      events.collect { case sigil.provider.ProviderEvent.TextDelta(t) => t }.mkString shouldBe "hello"
    }
  }

  "tear down" should {
    "dispose TestSigil" in spec.TestSigil.shutdown.map(_ => succeed)
  }
}
