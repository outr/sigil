package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{AgentState, Message, ModeChange, ToolInvoke}
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant}
import sigil.provider.{GenerationSettings, Instructions, Mode, Provider}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal, ToolDelta}
import sigil.tool.{Tool, ToolInput}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

import scala.concurrent.duration.*

/**
 * End-to-end exercise of the framework dispatcher (`Sigil.publish`):
 * external signal → persist → broadcast → fan-out → agent self-loop →
 * AgentState lifecycle. Asserts on what the [[RecordingBroadcaster]] sees.
 *
 * Extend by providing `provider` and `modelId`.
 */
trait AbstractDispatcherSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  protected def provider: Task[Provider]

  protected def modelId: Id[Model]

  /** Wire the spec's provider into TestSigil so `providerFor` returns it. */
  TestSigil.setProvider(provider)

  /** Tool names the test agent advertises. CoreTools' names + the synthetic
    * SendSlackMessageTool so `find_capability` has a catalog entry. */
  protected def toolNames: List[String] =
    CoreTools.coreToolNames :+ SendSlackMessageTool.schema.name

  protected def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = toolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0))
    )

  /** Poll-based wait for the agent to reach Idle (terminal AgentStateDelta).
    * The broadcaster captures every signal; once we see an Idle/Complete
    * delta for the AgentState lock id, we know the chain settled. */
  protected def awaitIdle(broadcaster: RecordingBroadcaster, timeoutMs: Long = 30000): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeoutMs
    def loop: Task[Unit] = Task.defer {
      val isIdle = broadcaster.recorded.exists {
        case d: AgentStateDelta =>
          d.activity.contains(AgentActivity.Idle) && d.state.contains(EventState.Complete)
        case _ => false
      }
      if (isIdle) Task.unit
      else if (System.currentTimeMillis() > deadline)
        Task.error(new RuntimeException(s"awaitIdle timed out after ${timeoutMs}ms"))
      else Task.sleep(50.millis).flatMap(_ => loop)
    }
    loop
  }

  protected def setUp(): RecordingBroadcaster = {
    val recorder = new RecordingBroadcaster
    TestSigil.setBroadcaster(recorder)
    recorder
  }

  /** Upsert a `Conversation` carrying the test agent in its `participants`
    * list. Specs use this before publishing the external Message so the
    * dispatcher's fan-out finds the agent on the persisted record. */
  protected def upsertConversationWithAgent(convId: Id[Conversation]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(_id = convId, participants = List(makeAgent()))
    ))).unit

  protected def upsertEmptyConversation(convId: Id[Conversation]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(_id = convId)
    ))).unit

  getClass.getSimpleName should {
    "drive a streaming respond from an external Message through the dispatcher" in {
      val recorder = setUp()
      val conversationId = Conversation.id("dispatcher-streaming-conversation")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        content = Vector(ResponseContent.Text("What is 2+2? Respond with just the number."))
      )

      val task = for {
        _ <- upsertConversationWithAgent(conversationId)
        _ <- TestSigil.publish(userMessage)
        _ <- awaitIdle(recorder)
      } yield recorder.recorded

      task.map { signals =>
        // External Message persisted + broadcast first.
        signals.headOption shouldBe Some(userMessage)

        // Agent claim emitted as Active Thinking AgentState.
        val agentStates = signals.collect { case a: AgentState => a }
        agentStates should not be empty
        agentStates.head.activity shouldBe AgentActivity.Thinking
        agentStates.head.state shouldBe EventState.Active

        // Streaming respond produced a ToolInvoke + Message + content + terminal deltas.
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes.exists(_.toolName == "respond") shouldBe true

        val messages = signals.collect { case m: Message => m }.filter(_.participantId == TestAgent)
        messages should not be empty

        val typingDeltas = signals.collect {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Typing) => d
        }
        typingDeltas should not be empty

        val terminalIdle = signals.reverseIterator.collectFirst {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Idle) => d
        }
        terminalIdle.flatMap(_.state) shouldBe Some(EventState.Complete)
      }
    }

    "self-loop after an atomic change_mode call" in {
      val recorder = setUp()
      val conversationId = Conversation.id("dispatcher-changemode-conversation")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        content = Vector(ResponseContent.Text("I need to write a Scala function."))
      )

      val task = for {
        _ <- upsertConversationWithAgent(conversationId)
        _ <- TestSigil.publish(userMessage)
        _ <- awaitIdle(recorder, timeoutMs = 60000)
      } yield recorder.recorded

      task.map { signals =>
        // The atomic change_mode must have fired at least once.
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes.exists(_.toolName == "change_mode") shouldBe true

        val modeChanges = signals.collect { case m: ModeChange => m }
        modeChanges should not be empty
        modeChanges.head.mode shouldBe Mode.Coding

        // Exactly one AgentState claim — the self-loop holds the lock across
        // every iteration in the chain.
        val agentStates = signals.collect { case a: AgentState => a }
        agentStates should have size 1

        // ModeChange passes TriggerFilter, so the self-loop ran the agent
        // again — assert at least 2 ToolInvokes (or 1 ToolInvoke + a
        // respond Message — either way, more than just the initial
        // change_mode).
        val totalToolInvokes = toolInvokes.size
        totalToolInvokes should be >= 2

        // Terminal AgentStateDelta(Idle, Complete) — either because the
        // agent eventually responded, hit no_response, or the iteration
        // guard fired (still terminates cleanly).
        val terminalIdle = signals.reverseIterator.collectFirst {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Idle) => d
        }
        terminalIdle.flatMap(_.state) shouldBe Some(EventState.Complete)
      }
    }

    "no-op fan-out when no participants match" in {
      val recorder = new RecordingBroadcaster
      TestSigil.setBroadcaster(recorder)

      val conversationId = Conversation.id("dispatcher-noparticipants-conversation")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        content = Vector(ResponseContent.Text("hello"))
      )

      val task = for {
        _ <- upsertEmptyConversation(conversationId)
        _ <- TestSigil.publish(userMessage)
      } yield recorder.recorded

      task.map { signals =>
        // External Message for this conversation broadcast even with no
        // participants registered. (The recorder may also contain bleed-over
        // from prior tests' lock-cleanup fibers; scope assertions to this
        // conversation.)
        val myConv = signals.filter(_.conversationId == conversationId)
        myConv should contain(userMessage)
        // No agent ran for this conversation, so no AgentState appears.
        myConv.collect { case a: AgentState => a } shouldBe empty
      }
    }
  }
}
