package spec

import fabric.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.debug.JsonLinesInterceptor

/**
 * #322 — the wire-log interceptor reassembles a streamed SSE response
 * (split `delta.content` + split tool-call `arguments`) into a readable
 * final message + complete tool calls, instead of logging the raw
 * fragmented `data:` stream.
 */
class SseReassemblySpec extends AnyWordSpec with Matchers {

  // content split across 2 chunks; one tool call whose arguments span 3.
  private val sse =
    """data: {"choices":[{"delta":{"role":"assistant","content":"Vague user "}}]}
      |
      |data: {"choices":[{"delta":{"content":"instruction"}}]}
      |
      |data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"report_progress","arguments":"{\"meaningfulProgress\":"}}]}}]}
      |
      |data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"false,\"shouldAskUser\":"}}]}}]}
      |
      |data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"true}"}}]}}]}
      |
      |data: [DONE]
      |""".stripMargin

  "JsonLinesInterceptor.reassembleSse (#322)" should {
    "detect an SSE body" in {
      JsonLinesInterceptor.looksLikeSse(sse) shouldBe true
      JsonLinesInterceptor.looksLikeSse("""{"choices":[]}""") shouldBe false
    }

    "concatenate split delta.content into the final message" in {
      val j = JsonLinesInterceptor.reassembleSse(sse)
      j.get("content").map(_.asString) shouldBe Some("Vague user instruction")
    }

    "reassemble a tool call's arguments split across chunks into one value" in {
      val j = JsonLinesInterceptor.reassembleSse(sse)
      val calls = j.get("tool_calls").map(_.asVector).getOrElse(Vector.empty)
      calls should have size 1
      val call = calls.head
      call.get("name").map(_.asString) shouldBe Some("report_progress")
      call.get("id").map(_.asString) shouldBe Some("call_1")
      val args = call.get("arguments").map(_.asString).getOrElse("")
      args shouldBe """{"meaningfulProgress":false,"shouldAskUser":true}"""
    }

    "fall back to the raw body for an unrecognised SSE shape" in {
      val weird = "data: not-json\n\ndata: [DONE]\n"
      JsonLinesInterceptor.reassembleSse(weird) shouldBe str(weird)
    }
  }
}
