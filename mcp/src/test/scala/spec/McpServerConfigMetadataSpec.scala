package spec

import fabric.rw.*
import fabric.{Json, Obj}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.mcp.{McpServerConfig, McpTransport}

/**
 * `metadata` is where an app marks a config for its own transport (or
 * stashes routing data). It is additive: rows persisted before the
 * field existed carry no `metadata` key and must still decode.
 */
class McpServerConfigMetadataSpec extends AnyWordSpec with Matchers {
  // `space` is a PolyType — populate the discriminators without opening a store.
  TestMcpSigil.polymorphicRegistrations.sync()

  private val config = McpServerConfig(name = "probe", transport = McpTransport.Stdio("noop", Nil))

  "McpServerConfig" should {
    "decode a persisted row that predates the metadata field" in {
      val legacy: Json = config.json match {
        case o: Obj =>
          o.value.contains("metadata") shouldBe true
          Obj(o.value - "metadata")
        case other => fail(s"expected an object encoding, got $other")
      }
      legacy.as[McpServerConfig].metadata shouldBe Map.empty
    }

    "round-trip app-supplied metadata" in {
      val marked = config.copy(metadata = Map("transport" -> "app-socket", "userId" -> "u-17"))
      marked.json.as[McpServerConfig].metadata shouldBe Map("transport" -> "app-socket", "userId" -> "u-17")
    }
  }
}
