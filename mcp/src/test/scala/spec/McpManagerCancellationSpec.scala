package spec

import fabric.{Json, Obj}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.mcp.{McpClient, McpPrompt, McpResource, McpServerConfig, McpToolDefinition, McpTransport}
import sigil.participant.{AgentParticipantId, ParticipantId}

import java.util.concurrent.ConcurrentLinkedQueue

class McpManagerCancellationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestMcpSigil.initFor(getClass.getSimpleName)

  case object TestAgent extends AgentParticipantId {
    override val value: String = "test-agent"
  }

  /** Stub client that records `cancelRequest` calls. */
  private class RecordingClient(override val config: McpServerConfig) extends McpClient {
    val cancellations: ConcurrentLinkedQueue[(Long, Option[String])] = new ConcurrentLinkedQueue()

    override def start(): Task[Unit] = Task.unit
    override def close(): Task[Unit] = Task.unit
    override def listTools(): Task[List[McpToolDefinition]] = Task.pure(Nil)
    override def callTool(name: String, arguments: Json, onWireId: Long => Unit = _ => ()): Task[Json] =
      Task.pure(Obj.empty)
    override def listResources(): Task[List[McpResource]] = Task.pure(Nil)
    override def readResource(uri: String): Task[Json] = Task.pure(Obj.empty)
    override def listPrompts(): Task[List[McpPrompt]] = Task.pure(Nil)
    override def getPrompt(name: String, arguments: Map[String, String] = Map.empty): Task[Json] = Task.pure(Obj.empty)
    override def cancelRequest(requestId: Long, reason: Option[String] = None): Task[Unit] = Task {
      cancellations.add((requestId, reason))
      ()
    }
  }

  private val cfg = McpServerConfig(
    name = "test-server",
    transport = McpTransport.Stdio("/bin/true", Nil)
  )

  "McpManager.cancelInFlight" should {
    "send notifications/cancelled for every in-flight call owned by the agent" in {
      val manager = TestMcpSigil.mcpManager
      val client  = new RecordingClient(cfg)
      manager.registerClientForTesting(cfg.name, client)
      // Simulate three in-flight calls owned by the agent.
      manager.registerInFlightForTesting(TestAgent, cfg.name, 1L)
      manager.registerInFlightForTesting(TestAgent, cfg.name, 2L)
      manager.registerInFlightForTesting(TestAgent, cfg.name, 3L)

      manager.cancelInFlight(TestAgent, Some("agent stopped")).map { _ =>
        import scala.jdk.CollectionConverters.*
        val cancelled = client.cancellations.iterator().asScala.toList
        cancelled.map(_._1).toSet shouldBe Set(1L, 2L, 3L)
        cancelled.foreach { case (_, reason) => reason shouldBe Some("agent stopped") }
        succeed
      }
    }

    "no-op when the agent has no in-flight calls" in {
      val manager = TestMcpSigil.mcpManager
      val client  = new RecordingClient(cfg)
      manager.registerClientForTesting("server-2", client)
      // No registrations for OtherAgent.
      case object OtherAgent extends AgentParticipantId {
        override val value: String = "other-agent"
      }
      manager.cancelInFlight(OtherAgent, None).map { _ =>
        client.cancellations.isEmpty shouldBe true
      }
    }
  }

  "tear down" should {
    "dispose TestMcpSigil" in TestMcpSigil.shutdown.map(_ => succeed)
  }
}
