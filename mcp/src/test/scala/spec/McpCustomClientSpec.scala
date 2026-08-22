package spec

import fabric.{obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.mcp.{HttpSseMcpClient, McpServerConfig, McpTransport, StdioMcpClient}
import sigil.participant.AgentParticipantId
import sigil.provider.ConversationMode
import sigil.tool.DiscoveryRequest
import spice.net.{TLDValidation, URL}

import scala.jdk.CollectionConverters.*

/**
 * An MCP server reachable only through a transport the app owns — a
 * hosted deployment whose server runs on an end user's machine, behind
 * NAT, reachable only over a socket that machine opened outbound —
 * needs an app-supplied [[sigil.mcp.McpClient]]. The framework's
 * built-in transports both assume the client can initiate, so the host
 * hook is the only route; these assertions drive it end-to-end,
 * including the collaborators (sampling handler, notification listener)
 * the built-ins get for free.
 */
class McpCustomClientSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  CustomClientMcpSigil.initFor(getClass.getSimpleName)

  case object TestAgent extends AgentParticipantId {
    override val value: String = "test-agent"
  }

  private def url(value: String): URL =
    URL.get(value, tldValidation = TLDValidation.Off).fold(e => throw new IllegalArgumentException(e.toString), identity)

  private val manager = CustomClientMcpSigil.mcpManager

  /** A URL only the end user's own machine can reach — the framework's
    * HTTP+SSE client could never connect to it from the host. */
  private val tunnelled = McpServerConfig(
    name = "user-laptop",
    transport = McpTransport.HttpSse(url("http://localhost:9000")),
    prefix = Some("laptop_"),
    metadata = Map("transport" -> CustomClientMcpSigil.Marker)
  )

  private val stdio = McpServerConfig(name = "local-stdio", transport = McpTransport.Stdio("noop", Nil))
  private val httpSse = McpServerConfig(name = "hosted-sse", transport = McpTransport.HttpSse(url("https://mcp.example.com")))

  "an app-supplied McpClient" should {
    "serve a marked config end-to-end" in {
      manager.addConfig(tunnelled).flatMap(_ => manager.listTools(tunnelled.name)).map { tools =>
        tools.map(_.name) shouldBe List("tunnelled_echo")
        val client = CustomClientMcpSigil.suppliedClient(tunnelled.name).getOrElse(fail("host was never asked for a client"))
        client.starts.get() shouldBe 1
        client.listToolsCalls.get() shouldBe 1
      }
    }

    "surface its tools to the agent through McpToolFinder" in {
      val request = DiscoveryRequest(
        keywords = "mcp",
        chain = Nil,
        mode = ConversationMode,
        callerSpaces = Set.empty
      )
      CustomClientMcpSigil.mcpToolFinder(request).map { tools =>
        tools.map(_.name.value) shouldBe List("laptop_tunnelled_echo")
      }
    }

    "invalidate the manager's tool cache when it fires a list_changed notification" in {
      val client = CustomClientMcpSigil.suppliedClient(tunnelled.name).getOrElse(fail("host was never asked for a client"))
      val before = client.listToolsCalls.get()
      manager.listTools(tunnelled.name).flatMap { _ =>
        client.listToolsCalls.get() shouldBe before
        client.fireToolsListChanged().flatMap(_ => manager.listTools(tunnelled.name)).map { tools =>
          tools.map(_.name) shouldBe List("tunnelled_echo")
          client.listToolsCalls.get() shouldBe before + 1
        }
      }
    }

    "answer tool calls through the app's transport" in {
      manager.callTool(tunnelled.name, "tunnelled_echo", obj("value" -> str("ping")), TestAgent).map { result =>
        val client = CustomClientMcpSigil.suppliedClient(tunnelled.name).getOrElse(fail("host was never asked for a client"))
        client.toolCalls.iterator().asScala.toList.map(_._1) shouldBe List("tunnelled_echo")
        result.get("content").map(_.asVector.toList).getOrElse(Nil).flatMap(_.get("text")).map(_.asString) shouldBe
          List("tunnelled_echo handled in-process")
      }
    }
  }

  "the built-in transports" should {
    "still serve a config the host declines" in {
      manager.clientImplementationFor(stdio) shouldBe a[StdioMcpClient]
      manager.clientImplementationFor(httpSse) shouldBe a[HttpSseMcpClient]
      succeed
    }

    "yield to the host for a marked config" in {
      manager.clientImplementationFor(tunnelled) shouldBe a[InProcessMcpClient]
      succeed
    }
  }

  "tear down" should {
    "dispose CustomClientMcpSigil" in CustomClientMcpSigil.shutdown.map(_ => succeed)
  }
}
