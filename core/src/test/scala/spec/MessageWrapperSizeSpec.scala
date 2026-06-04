package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.{Stream, Task}
import sigil.provider.{MessageContent, Provider, ProviderCall, ProviderMessage, ProviderType, ToolCallMessage}
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}

/**
 * Coverage for sigil bug #44 — `Provider.estimateMessage` must
 * include the JSON-RPC wrapper around each Assistant tool call, the
 * envelope around each ToolResult, and the Reasoning body. The prior
 * implementation flat-counted `+3` per message and zero for
 * Reasoning, accumulating a 1-3K wire-token undercount on tool-using
 * conversations.
 */
class MessageWrapperSizeSpec extends AnyWordSpec with Matchers {

  // Test-friendly Provider exposing the protected estimator.
  private object TestProvider extends Provider {
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def `type`: ProviderType = ProviderType.OpenAI
    override def call(input: ProviderCall): Stream[_root_.sigil.provider.ProviderEvent] = Stream.empty
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new RuntimeException("not implemented"))
    def measure(m: ProviderMessage, tok: Tokenizer): Int = estimateMessage(m, tok)
  }

  private val tok = HeuristicTokenizer

  "Provider.estimateMessage" should {

    "count more for an Assistant carrying a tool call than for a plain Assistant message" in {
      val plain = ProviderMessage.Assistant(content = "hello", toolCalls = Nil)
      val withCall = ProviderMessage.Assistant(
        content = "hello",
        toolCalls = List(ToolCallMessage(
          id = "call-abc-123",
          name = "do_something",
          argsJson = """{"x":42,"y":"value"}"""
        ))
      )
      TestProvider.measure(withCall, tok) should be > TestProvider.measure(plain, tok)
    }

    "include the call id in the Assistant tool-call cost" in {
      val short = ProviderMessage.Assistant(
        content = "",
        toolCalls = List(ToolCallMessage(
          id = "x",
          name = "n",
          argsJson = "{}"
        )))
      val long = ProviderMessage.Assistant(
        content = "",
        toolCalls = List(ToolCallMessage(
          id = "x" * 200,
          name = "n",
          argsJson = "{}"
        )))
      TestProvider.measure(long, tok) should be > TestProvider.measure(short, tok)
    }

    "count the call id on ToolResult" in {
      val short = ProviderMessage.ToolResult(toolCallId = "x", content = "ok")
      val long = ProviderMessage.ToolResult(toolCallId = "x" * 200, content = "ok")
      TestProvider.measure(long, tok) should be > TestProvider.measure(short, tok)
    }

    "count the Reasoning summary body (no longer zero)" in {
      val empty = ProviderMessage.Reasoning(
        providerItemId = "rs_x",
        summary = Nil,
        encryptedContent = None
      )
      val rich = ProviderMessage.Reasoning(
        providerItemId = "rs_x",
        summary = List("step 1: think about the user's request very carefully" * 10),
        encryptedContent = None
      )
      TestProvider.measure(rich, tok) should be > TestProvider.measure(empty, tok)
      // Empty Reasoning still carries the per-message envelope.
      TestProvider.measure(empty, tok) should be > 0
    }

    "count the encryptedContent body on Reasoning (counted even though opaque)" in {
      val noCot = ProviderMessage.Reasoning(
        providerItemId = "rs_x",
        summary = Nil,
        encryptedContent = None
      )
      val withCot = ProviderMessage.Reasoning(
        providerItemId = "rs_x",
        summary = Nil,
        encryptedContent = Some("opaque-blob-" * 100)
      )
      TestProvider.measure(withCot, tok) should be > TestProvider.measure(noCot, tok)
    }

    "scale per-call linearly across multiple Assistant tool calls" in {
      val one = ProviderMessage.Assistant(
        content = "",
        toolCalls = List(ToolCallMessage("call-1", "tool", "{\"a\":1}"))
      )
      val three = ProviderMessage.Assistant(
        content = "",
        toolCalls = List(
          ToolCallMessage("call-1", "tool", "{\"a\":1}"),
          ToolCallMessage("call-2", "tool", "{\"a\":1}"),
          ToolCallMessage("call-3", "tool", "{\"a\":1}")
        )
      )
      val oneCost = TestProvider.measure(one, tok)
      val threeCost = TestProvider.measure(three, tok)
      // 3× minus the shared per-message envelope (which is counted once).
      threeCost.toDouble should be > (oneCost * 2.5)
    }
  }
}
