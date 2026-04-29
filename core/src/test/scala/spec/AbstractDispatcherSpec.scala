package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{AgentState, Event, Message, ModeChange, Stop, ToolInvoke}
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant}
import sigil.provider.{GenerationSettings, Instructions, Mode, ConversationMode, Provider}
import sigil.signal.{AgentActivity, AgentStateDelta, Delta, EventState, MessageDelta, Signal, ToolDelta}
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tool.core.{ChangeModeTool, CoreTools}
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

  /** Tool names the test agent advertises. CoreTools' default roster
    * plus the synthetic SendSlackMessageTool (so `find_capability` has
    * an app-contributed catalog entry to surface), the opt-in
    * `change_mode` tool (required by the change_mode self-loop test —
    * `change_mode` is not in `CoreTools.all`), and the non-core
    * SleepTool (required by the graceful/force stop tests). */
  protected def toolNames: List[ToolName] =
    CoreTools.coreToolNames ++ List(
      ChangeModeTool.schema.name,
      SendSlackMessageTool.schema.name,
      sigil.tool.util.SleepTool.schema.name
    )

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
    recorder.attach(TestSigil)
    recorder
  }

  /** Upsert a `Conversation` carrying the test agent in its `participants`
    * list. Specs use this before publishing the external Message so the
    * dispatcher's fan-out finds the agent on the persisted record. */
  protected def upsertConversationWithAgent(convId: Id[Conversation]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(topics = TestTopicStack, _id = convId, participants = List(makeAgent()))
    ))).unit

  /** Same as [[upsertConversationWithAgent]] but with a custom tool roster
    * for the agent. Used by the discovery tests to stand up an agent whose
    * default roster deliberately doesn't include the target tool, so the
    * test exercises `find_capability` → `suggestedTools` → next-turn call. */
  protected def upsertConversationWithAgent(convId: Id[Conversation], tools: List[ToolName]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(topics = TestTopicStack, _id = convId, participants = List(
        DefaultAgentParticipant(
          id = TestAgent,
          modelId = modelId,
          toolNames = tools,
          instructions = Instructions(),
          generationSettings = GenerationSettings(maxOutputTokens = Some(300), temperature = Some(0.0))
        )
      ))
    ))).unit

  protected def upsertEmptyConversation(convId: Id[Conversation]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(topics = TestTopicStack, _id = convId)
    ))).unit

  getClass.getSimpleName should {
    "drive a streaming respond from an external Message through the dispatcher" in {
      val recorder = setUp()
      val conversationId = Conversation.id("dispatcher-streaming-conversation")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("What is 2+2? Respond with just the number.")),
        state = EventState.Complete
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
        toolInvokes.exists(_.toolName == ToolName("respond")) shouldBe true

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
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("I need to write a Scala function.")),
        state = EventState.Complete
      )

      val task = for {
        _ <- upsertConversationWithAgent(conversationId)
        _ <- TestSigil.publish(userMessage)
        _ <- awaitIdle(recorder, timeoutMs = 60000)
      } yield recorder.recorded

      task.map { signals =>
        // The atomic change_mode must have fired at least once.
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes.exists(_.toolName == ToolName("change_mode")) shouldBe true

        val modeChanges = signals.collect { case m: ModeChange => m }
        modeChanges should not be empty
        modeChanges.head.mode shouldBe TestCodingMode

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

    "honor a graceful Stop published mid-turn (no respond iteration after the in-flight one)" in {
      val recorder = setUp()
      val conversationId = Conversation.id("dispatcher-stop-graceful")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text(
          "IMPORTANT: Your first action must be to call the `sleep` tool with millis=1500. " +
            "Do not respond, do not change mode, do not call any other tool first — call sleep first. " +
            "Only AFTER the sleep tool returns, you may call respond with the text 'done'."
        )),
        state = EventState.Complete
      )

      val task = for {
        _ <- upsertConversationWithAgent(conversationId)
        _ <- TestSigil.publish(userMessage)
        // Give the agent enough time to fire its first tool call (sleep)
        // but far less than sleep's 1500ms so the stop publishes while
        // the agent is still mid-sleep.
        _ <- Task.sleep(500.millis)
        _ <- TestSigil.stop(
          conversationId = conversationId,
          requestedBy = TestUser,
          targetParticipantId = Some(TestAgent),
          force = false,
          reason = Some("test graceful stop")
        )
        _ <- awaitIdle(recorder, timeoutMs = 10000)
      } yield recorder.recorded

      task.map { signals =>
        // The Stop event should appear.
        val stops = signals.collect { case s: Stop => s }
        stops should not be empty
        stops.head.force shouldBe false
        stops.head.targetParticipantId shouldBe Some(TestAgent)

        // After sleep completes, the loop checks the flag and exits — the
        // agent MUST NOT have fired a `respond` iteration.
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes.map(_.toolName) should not contain ToolName("respond")

        // The claim should still be released cleanly.
        val terminalIdle = signals.reverseIterator.collectFirst {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Idle) => d
        }
        terminalIdle.flatMap(_.state) shouldBe Some(EventState.Complete)
      }
    }

    "honor a force Stop published mid-respond (stream interrupts before the Complete delta)" in {
      val recorder = setUp()
      val conversationId = Conversation.id("dispatcher-stop-force")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text(
          "Respond immediately with a very long, detailed essay about the history of the Roman Empire. " +
            "Aim for at least 500 words with multiple paragraphs. Begin the response right away."
        )),
        state = EventState.Complete
      )

      val task = for {
        _ <- upsertConversationWithAgent(conversationId)
        _ <- TestSigil.publish(userMessage)
        // Give the respond stream enough time to start emitting content.
        _ <- Task.sleep(700.millis)
        _ <- TestSigil.stop(
          conversationId = conversationId,
          requestedBy = TestUser,
          targetParticipantId = Some(TestAgent),
          force = true,
          reason = Some("test force stop")
        )
        _ <- awaitIdle(recorder, timeoutMs = 15000)
      } yield recorder.recorded

      task.map { signals =>
        val stops = signals.collect { case s: Stop => s }
        stops should not be empty
        stops.head.force shouldBe true

        // The distinctive force-stop signature: the agent's streaming
        // Message never reaches a Complete state. takeWhile cut the
        // stream before `ToolCallComplete` could emit the closing
        // MessageDelta(state = Complete).
        val agentMessages = signals.collect { case m: Message => m }.filter(_.participantId == TestAgent)
        if (agentMessages.nonEmpty) {
          val msgId = agentMessages.head._id
          val closingDeltas = signals.collect {
            case d: MessageDelta if d.target == msgId && d.state.contains(EventState.Complete) => d
          }
          closingDeltas shouldBe empty
        }

        // Claim still released cleanly.
        val terminalIdle = signals.reverseIterator.collectFirst {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Idle) => d
        }
        terminalIdle.flatMap(_.state) shouldBe Some(EventState.Complete)
      }
    }

    "invoke find_capability when the user asks for an action not in the agent's roster" in {
      val recorder = setUp()
      val conversationId = Conversation.id("dispatcher-discovery-invoke")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("Please wait 200 milliseconds, then respond with 'done'.")),
        state = EventState.Complete
      )

      // Lean roster: core only — NO sleep, NO slack. Sleep must be discovered.
      val task = for {
        _ <- upsertConversationWithAgent(conversationId, CoreTools.coreToolNames)
        _ <- TestSigil.publish(userMessage)
        _ <- awaitIdle(recorder, timeoutMs = 20000)
      } yield recorder.recorded

      task.map { signals =>
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        // The agent's first action on an unknown-capability request should
        // be a find_capability call.
        toolInvokes.map(_.toolName) should contain(ToolName("find_capability"))
      }
    }

    "make a discovered tool callable on the next turn (find_capability → suggestedTools → invoke)" in {
      val recorder = setUp()
      val conversationId = Conversation.id("dispatcher-discovery-call")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text(
          "The user needs to pause for 200 milliseconds. Use find_capability with the keywords " +
            "\"sleep wait delay\" to find the tool, then call it with millis=200, then respond with 'done'."
        )),
        state = EventState.Complete
      )

      val task = for {
        _ <- upsertConversationWithAgent(conversationId, CoreTools.coreToolNames)
        _ <- TestSigil.publish(userMessage)
        _ <- awaitIdle(recorder, timeoutMs = 30000)
      } yield recorder.recorded

      task.map { signals =>
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        val names = toolInvokes.map(_.toolName)
        // find_capability must have fired (agent discovered the tool)...
        names should contain(ToolName("find_capability"))
        // ...and the sleep tool must have been called afterward, proving the
        // suggestedTools-union path made it callable on the next turn even
        // though `sleep` is NOT in the agent's durable toolNames.
        names should contain(ToolName("sleep"))
      }
    }

    "decay suggestedTools when the agent doesn't use them within one turn" in {
      // Seed the view with a suggestion manually — simulates the state right
      // after a ToolResults update, without having to coax an LLM through the
      // full discovery flow. Then publish a trivial user message and assert
      // that after the agent's turn, the suggestion is gone.
      val recorder = setUp()
      val conversationId = Conversation.id("dispatcher-suggested-decay")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("Just respond with 'hi' — no other tools needed.")),
        state = EventState.Complete
      )

      val task = for {
        _ <- upsertConversationWithAgent(conversationId, CoreTools.coreToolNames)
        // Seed a stale suggestion that the agent should ignore.
        _ <- TestSigil.updateProjection(conversationId, TestAgent)(
          _.copy(suggestedTools = List(ToolName("sleep")))
        )
        before <- TestSigil.viewFor(conversationId)
        _ <- TestSigil.publish(userMessage)
        _ <- awaitIdle(recorder, timeoutMs = 20000)
        after <- TestSigil.viewFor(conversationId)
      } yield (before, after)

      task.map { case (before, after) =>
        // Sanity: suggestion was actually present before the turn ran.
        before.projectionFor(TestAgent).suggestedTools shouldBe List(ToolName("sleep"))
        // After an agent turn that didn't trigger a new find_capability,
        // the stale suggestion is cleared.
        after.projectionFor(TestAgent).suggestedTools shouldBe empty
      }
    }

    "no-op fan-out when no participants match" in {
      val recorder = new RecordingBroadcaster
      recorder.attach(TestSigil)

      val conversationId = Conversation.id("dispatcher-noparticipants-conversation")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        topicId = TestTopicId,
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
        val myConv = signals.collect {
          case e: Event if e.conversationId == conversationId => e: Signal
          case d: Delta if d.conversationId == conversationId => d: Signal
        }
        myConv should contain(userMessage)
        // No agent ran for this conversation, so no AgentState appears.
        myConv.collect { case a: AgentState => a } shouldBe empty
      }
    }
  }
}
