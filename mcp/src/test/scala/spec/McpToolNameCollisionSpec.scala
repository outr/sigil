package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.mcp.{McpServerConfig, McpTool, McpToolDefinition, McpTransport}

/**
 * Grammar sanitization is lossy: `fs.read` and `fs:read` both collapse
 * to `fs_read`, and two names differing only past the 64th character
 * truncate together. Mapping raw names naively meant the second tool
 * silently vanished from the roster. [[McpTool.resolveNames]] assigns a
 * distinct name to every raw name instead.
 */
class McpToolNameCollisionSpec extends AnyWordSpec with Matchers {

  private val config = McpServerConfig(name = "probe", transport = McpTransport.Stdio("noop", Nil))

  "McpTool.resolveNames" should {

    "give charset-colliding raw names distinct tool names" in {
      val resolved = McpTool.resolveNames(List("fs.read", "fs:read"))
      resolved should have size 2
      resolved.values.toSet should have size 2
      resolved.values.map(_.value).toSet should contain("fs_read")
    }

    "give truncation-colliding raw names distinct tool names" in {
      val prefix = "a" * 64
      val resolved = McpTool.resolveNames(List(prefix + "_one", prefix + "_two"))
      resolved.values.toSet should have size 2
      resolved.values.map(_.value.length).foreach(_ should be <= 64)
    }

    "leave non-colliding names untouched" in {
      val resolved = McpTool.resolveNames(List("fs_read", "fs_write"))
      resolved("fs_read").value shouldBe "fs_read"
      resolved("fs_write").value shouldBe "fs_write"
    }

    "assign the same names on repeat calls (stable, order-independent)" in {
      val a = McpTool.resolveNames(List("fs.read", "fs:read"))
      val b = McpTool.resolveNames(List("fs:read", "fs.read"))
      a shouldBe b
    }
  }

  "McpTool.specFor" should {

    "honor the collision-resolved name override" in {
      val resolved = McpTool.resolveNames(List("fs.read", "fs:read"))
      val specs = List("fs.read", "fs:read").map { raw =>
        McpTool.specFor(config, McpToolDefinition(name = raw, description = Some("probe")), resolved.get(raw))
      }
      specs.map(_.name).toSet should have size 2
    }
  }
}
