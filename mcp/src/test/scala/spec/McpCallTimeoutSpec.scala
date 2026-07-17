package spec

import fabric.{Json, Obj}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.mcp.{McpCallTimeoutException, McpClient, McpPrompt, McpResource, McpServerConfig, McpToolDefinition, McpTransport}
import sigil.participant.AgentParticipantId

import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/**
 * Proves #368 — an MCP tool call against a wedged / slow-importing server
 * must time out and surface a failure, not hang the turn indefinitely. The
 * live symptom was `bsp_inverse_sources` running 4+ minutes with nothing
 * surfaced because `McpManager.callTool` had no execution timeout.
 */
class McpCallTimeoutSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestMcpSigil.initFor(getClass.getSimpleName)

  override protected val testTimeout: FiniteDuration = 30.seconds

  private case object TimeoutAgent extends AgentParticipantId {
    override val value: String = "timeout-agent"
  }

  /**
   * `callTool` blocks far longer than the configured per-call timeout —
   * stands in for a build server stuck on a cold import.
   */
  private class HangingClient(override val config: McpServerConfig) extends McpClient {
    override def start(): Task[Unit] = Task.unit
    override def close(): Task[Unit] = Task.unit
    override def listTools(): Task[List[McpToolDefinition]] = Task.pure(Nil)
    override def callTool(name: String, arguments: Json, onWireId: Long => Unit = _ => ()): Task[Json] =
      Task.sleep(5.seconds).map(_ => Obj("content" -> fabric.arr()))
    override def listResources(): Task[List[McpResource]] = Task.pure(Nil)
    override def readResource(uri: String): Task[Json] = Task.pure(Obj.empty)
    override def listPrompts(): Task[List[McpPrompt]] = Task.pure(Nil)
    override def getPrompt(name: String, arguments: Map[String, String] = Map.empty): Task[Json] = Task.pure(Obj.empty)
    override def cancelRequest(requestId: Long, reason: Option[String] = None): Task[Unit] = Task.unit
  }

  /**
   * Returns immediately — guards that the timeout wrapper doesn't break
   * the normal fast path.
   */
  private class FastClient(override val config: McpServerConfig) extends McpClient {
    override def start(): Task[Unit] = Task.unit
    override def close(): Task[Unit] = Task.unit
    override def listTools(): Task[List[McpToolDefinition]] = Task.pure(Nil)
    override def callTool(name: String, arguments: Json, onWireId: Long => Unit = _ => ()): Task[Json] =
      Task.pure(Obj("content" -> fabric.arr(Obj("type" -> fabric.str("text"), "text" -> fabric.str("ok")))))
    override def listResources(): Task[List[McpResource]] = Task.pure(Nil)
    override def readResource(uri: String): Task[Json] = Task.pure(Obj.empty)
    override def listPrompts(): Task[List[McpPrompt]] = Task.pure(Nil)
    override def getPrompt(name: String, arguments: Map[String, String] = Map.empty): Task[Json] = Task.pure(Obj.empty)
    override def cancelRequest(requestId: Long, reason: Option[String] = None): Task[Unit] = Task.unit
  }

  "McpManager.callTool" should {
    "time out a hung call instead of blocking indefinitely" in {
      val cfg = McpServerConfig(
        name = "wedged-bsp",
        transport = McpTransport.Stdio("/bin/true", Nil),
        callTimeoutMs = 400L
      )
      val manager = TestMcpSigil.mcpManager
      manager.registerClientForTesting(cfg.name, new HangingClient(cfg))
      val start = System.currentTimeMillis()
      manager.callTool(cfg.name, "bsp_inverse_sources", Obj.empty, TimeoutAgent).attempt.map { result =>
        val elapsed = System.currentTimeMillis() - start
        withClue(s"result=$result elapsed=${elapsed}ms: ") {
          result match {
            case Failure(_: McpCallTimeoutException) => succeed
            case other => fail(s"expected McpCallTimeoutException, got $other")
          }
          elapsed should be < 3000L
        }
      }
    }

    "leave a fast call unaffected by the timeout wrapper" in {
      val cfg = McpServerConfig(
        name = "fast-server",
        transport = McpTransport.Stdio("/bin/true", Nil),
        callTimeoutMs = 400L
      )
      val manager = TestMcpSigil.mcpManager
      manager.registerClientForTesting(cfg.name, new FastClient(cfg))
      manager.callTool(cfg.name, "quick_tool", Obj.empty, TimeoutAgent).map { json =>
        json.get("content").map(_.asVector.size).getOrElse(0) shouldBe 1
      }
    }
  }

  "tear down" should {
    "dispose TestMcpSigil" in TestMcpSigil.shutdown.map(_ => succeed)
  }
}
