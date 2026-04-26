package spec

import lightdb.id.Id
import org.scalatest.{Assertion, BeforeAndAfterAll}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{GenerationSettings, Instructions, Provider}
import sigil.testkit.{ConversationHarness, ConversationSession}
import sigil.tool.ToolName
import sigil.tool.core.CoreTools
import spice.http.client.HttpClient
import spice.http.durable.{DurableSocketConfig, ReconnectStrategy}
import spice.net.*

import scala.concurrent.duration.*

/**
 * End-to-end conversation spec backed by a [[ConversationHarness]] —
 * real HTTP server with a `DurableSocketServer` mounted at `/ws`, a
 * client connecting from the test JVM, and `Sigil.signalsFor(viewer)`
 * bridged into the wire so agent-published Events ride to the client.
 *
 * Per-provider impls override only `provider` and `modelId`; the
 * server / client / agent / wire plumbing all lives in
 * [[ConversationHarness]] and is reusable by downstream apps.
 */
trait AbstractConversationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  // Each test issues 1-2 full agent turns (each ~2-5s on a local model);
  // the rapid-test default per-test timeout is 1 minute, which the
  // multi-turn case can edge past. 3 minutes is generous for any
  // realistic provider.
  override implicit protected val testTimeout: FiniteDuration = 3.minutes

  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProvider(provider)

  protected def provider: Task[Provider]
  protected def modelId: Id[Model]

  /** Tools the agent advertises. Defaults to `CoreTools.coreToolNames`
    * — apps can override to add app-specific tools to the roster. */
  protected def toolNames: List[ToolName] = CoreTools.coreToolNames

  /** Generation settings for the agent. 4000 max tokens leaves room for
    * the respond tool's `topicLabel` + `topicSummary` + content without
    * truncation; temperature 0.0 for reproducibility. */
  protected def generationSettings: GenerationSettings =
    GenerationSettings(maxOutputTokens = Some(4000), temperature = Some(0.0))

  protected def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = toolNames,
      instructions = Instructions(),
      generationSettings = generationSettings
    )

  protected lazy val harness: ConversationHarness =
    ConversationHarness(
      sigil = TestSigil,
      viewer = TestUser,
      conversationFactory = convId => Conversation(
        topics = List(TestTopicEntry),
        _id = convId,
        participants = List(makeAgent())
      )
    )

  protected def withClient(suffix: String)(f: ConversationSession => Task[Assertion]): Task[Assertion] =
    harness.withClient(suffix)(f)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    harness.start().sync()
  }

  override protected def afterAll(): Unit = {
    harness.stop().sync()
    super.afterAll()
  }

  // --- Default scenarios ---

  s"${getClass.getSimpleName} (DurableSocket end-to-end)" should {
    "round-trip a single user message and receive a non-empty agent reply" in {
      withClient("single-turn") { s =>
        s.send("Reply with the single word 'hi'.").map { reply =>
          ConversationSession.textOf(reply) should not be empty
          succeed
        }
      }
    }
    "carry context across multiple turns in the same conversation" in {
      withClient("multi-turn") { s =>
        for {
          _ <- s.send("My favorite color is blue. Acknowledge in one short sentence.")
          recall <- s.send("In one word, what color did I just say was my favorite?")
        } yield {
          ConversationSession.textOf(recall).toLowerCase should include("blue")
          succeed
        }
      }
    }
    "switch to the coding mode over the wire when the user asks to write code" in {
      withClient("mode-switch") { s =>
        for {
          _ <- s.send("Write me a Scala function that computes the factorial of n.")
          conv <- s.conversation
        } yield conv.currentMode.name should be(TestCodingMode.name)
      }
    }
    "replay missed events from SigilDB.events when a client resumes after disconnect" in {
      withClient("resume") { s =>
        for {
          firstReply <- s.send("Reply with the single word 'one'.")
          // Cache the highest seq, then disconnect.
          lastSeq = s.client.protocol.highestProcessedSeq
          _ = s.client.protocol.unbind()
          // Publish a SECOND user message while the client is detached.
          // The agent's response settles in SigilDB.events but doesn't
          // reach this disconnected client live.
          secondReply <- s.send("Reply with the single word 'two'.")
          // Reconnect using the stored cursor — replay should redeliver
          // the missed agent Message via SigilDbEventLog.replay.
          ws2 = HttpClient.url(url"ws://localhost".withPort(harness.serverPort).withPath(path"/ws")).webSocket()
          _ <- ws2.connect()
          _ = s.client.protocol.bind(ws2)
          _ = s.client.protocol.sendResume(s"resume-${rapid.Unique()}", lastSeq, s.convId.value)
          _ <- Task.sleep(2.seconds)
        } yield {
          ConversationSession.textOf(firstReply) should not be empty
          ConversationSession.textOf(secondReply) should not be empty
          firstReply._id should not be secondReply._id
          succeed
        }
      }
    }
  }
}
