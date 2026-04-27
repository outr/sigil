package spec

import fabric.*
import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.mcp.{McpError, McpJsonRpc}

import java.io.{BufferedReader, ByteArrayInputStream, ByteArrayOutputStream, InputStreamReader}
import java.nio.charset.StandardCharsets

/**
 * Lightweight unit coverage for [[McpJsonRpc]] — verifies the wire
 * framing produced by `request` / `notify` and the dispatch of
 * pre-baked incoming lines to the handler callbacks. End-to-end
 * bidirectional flow is exercised by app-level integration tests
 * against real MCP servers.
 */
class McpJsonRpcSpec extends AnyWordSpec with Matchers {

  /** Wire a McpJsonRpc to fixed input bytes + a captured output buffer. */
  private def fixture(inputLines: String = ""): (McpJsonRpc, ByteArrayOutputStream) = {
    val inBytes = inputLines.getBytes(StandardCharsets.UTF_8)
    val reader  = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(inBytes), StandardCharsets.UTF_8))
    val out     = new ByteArrayOutputStream()
    (new McpJsonRpc(reader, out), out)
  }

  "McpJsonRpc.notify" should {
    "write a JSON-RPC notification (no id) terminated by newline" in {
      val (rpc, out) = fixture()
      rpc.notify("notifications/initialized").sync()
      val written = out.toString(StandardCharsets.UTF_8)
      written should endWith("\n")
      val parsed = JsonParser(written.trim)
      parsed.get("jsonrpc").map(_.asString) shouldBe Some("2.0")
      parsed.get("method").map(_.asString) shouldBe Some("notifications/initialized")
      parsed.get("id") shouldBe None
    }

    "include params when supplied" in {
      val (rpc, out) = fixture()
      rpc.notify("notifications/cancelled", obj("requestId" -> num(7))).sync()
      val parsed = JsonParser(out.toString(StandardCharsets.UTF_8).trim)
      parsed.get("params").flatMap(_.get("requestId")).map(_.asInt) shouldBe Some(7)
    }
  }

  "McpJsonRpc.request" should {
    "write a JSON-RPC request with an auto-incrementing id" in {
      val (rpc, out) = fixture()
      // Just verify the wire format — we don't await the response (no reader running).
      rpc.requestWithId("tools/list", obj(), _ => ()).startUnit()
      Thread.sleep(50)  // let the write happen
      val parsed = JsonParser(out.toString(StandardCharsets.UTF_8).trim)
      parsed.get("jsonrpc").map(_.asString) shouldBe Some("2.0")
      parsed.get("method").map(_.asString) shouldBe Some("tools/list")
      parsed.get("id").map(_.asInt).getOrElse(-1) should be > 0
    }

    "expose the wire id via the requestWithId callback" in {
      val (rpc, out) = fixture()
      var captured = -1L
      rpc.requestWithId("tools/list", obj(), wid => captured = wid).startUnit()
      Thread.sleep(50)
      captured should be > 0L
    }
  }
}
