package spec

import fabric.*
import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.{JsonInput, ToolInput, ToolOutput}

/**
 * MCP tool persistence round-trip after the mixin-level double
 * registration was removed: [[sigil.mcp.McpToolFinder.toolIO]] is now
 * the single source that registers `JsonInput` (MCP tool-call args)
 * and the ToolOutput codecs into the polymorphic RWs at init. A
 * `ToolInvoke` carrying a JsonInput must survive the polymorphic
 * `RW[ToolInput]` round-trip — the persistence path every MCP tool
 * call takes.
 */
class McpToolInputRegistrationSpec extends AnyWordSpec with Matchers {
  TestMcpSigil.initFor(getClass.getSimpleName)

  TestMcpSigil.polymorphicRegistrations.sync()

  "McpToolFinder.toolIO registration" should {

    "round-trip a JsonInput through the polymorphic RW[ToolInput]" in {
      val original: ToolInput = JsonInput(obj("city" -> str("Oslo"), "days" -> num(3)))
      val rw = summon[RW[ToolInput]]
      val restored = rw.write(rw.read(original))
      restored shouldBe a[JsonInput]
      // JsonInput is a JsonWrapper — the poly discriminator rides the
      // wrapped payload on the round trip; the caller's fields survive.
      val payload = restored.asInstanceOf[JsonInput].json
      payload("city") shouldBe str("Oslo")
      payload("days") shouldBe num(3)
    }

    "round-trip a text ToolOutput through the polymorphic RW[ToolOutput]" in {
      val original: ToolOutput = sigil.tool.TextToolOutput("server said hi")
      val rw = summon[RW[ToolOutput]]
      val restored = rw.write(rw.read(original))
      restored shouldBe sigil.tool.TextToolOutput("server said hi")
    }
  }

  "tear down" should {
    "dispose TestMcpSigil" in TestMcpSigil.shutdown.map(_ => succeed).sync()
  }
}
