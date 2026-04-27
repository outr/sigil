package spec

import fabric.{Json, Obj, num, obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.mcp.{McpServerConfig, McpTransport, SamplingHandler, StdioMcpClient}

/**
 * End-to-end stdio integration test against [[EmbeddedMcpServerMain]],
 * launched as a child JVM. Exercises the full client surface:
 * initialize handshake, tools/resources/prompts discovery, tool
 * invocation with arguments, resource read, prompt fetch.
 */
class McpStdioIntegrationSpec extends AnyWordSpec with Matchers {

  private val javaBin: String = sys.props.get("java.home").map(h => s"$h/bin/java").getOrElse("java")
  private val classpath: String = sys.props("java.class.path")
  private def cfg: McpServerConfig = McpServerConfig(
    name      = "embedded",
    transport = McpTransport.Stdio(javaBin, List("-cp", classpath, "spec.EmbeddedMcpServerMain"))
  )

  "StdioMcpClient against an embedded server" should {
    "initialize, list tools, and invoke a tool" in {
      val client = new StdioMcpClient(cfg, SamplingHandler.Refusing)
      try {
        client.start().sync()
        val tools = client.listTools().sync()
        tools.map(_.name) shouldBe List("echo")
        tools.head.description shouldBe Some("echo the supplied text")

        val result = client.callTool("echo", obj("text" -> str("hi"))).sync()
        val content = result.get("content").map(_.asVector.toList).getOrElse(Nil)
        content.headOption.flatMap(_.get("text")).map(_.asString) shouldBe Some("echoed:hi")
      } finally client.close().sync()
    }

    "list and read resources" in {
      val client = new StdioMcpClient(cfg, SamplingHandler.Refusing)
      try {
        client.start().sync()
        val rs = client.listResources().sync()
        rs.map(_.uri) shouldBe List("test://example")
        val body = client.readResource("test://example").sync()
        val contents = body.get("contents").map(_.asVector.toList).getOrElse(Nil)
        contents.headOption.flatMap(_.get("text")).map(_.asString) shouldBe Some("hello from embedded mcp")
      } finally client.close().sync()
    }

    "list and fetch prompts" in {
      val client = new StdioMcpClient(cfg, SamplingHandler.Refusing)
      try {
        client.start().sync()
        val ps = client.listPrompts().sync()
        ps.map(_.name) shouldBe List("greet")
        val got = client.getPrompt("greet", Map("name" -> "Sigil")).sync()
        got.get("description").map(_.asString) shouldBe Some("greeting")
      } finally client.close().sync()
    }
  }
}
