package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{ContextFrame, Conversation, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, ModeChange, ToolInvoke}
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant}
import sigil.provider.{GenerationSettings, Instructions, Mode, Provider}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, MessageDelta, Signal, ToolDelta}
import sigil.tool.{Tool, ToolInput}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

/**
 * Drives an [[AgentParticipant]] through a live LLM provider and asserts on
 * the resulting `Signal` stream produced by `defaultProcess` directly. This
 * spec exercises the lower layer — the framework's `Sigil.publish` /
 * dispatcher / `AgentState` lifecycle is covered by `AbstractDispatcherSpec`.
 *
 * Extend by providing `provider` and `modelId`.
 */
trait AbstractOrchestratorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  protected def provider: Task[Provider]

  protected def modelId: Id[Model]

  /**
   * Wire the spec's provider into TestSigil so `providerFor` returns it.
   */
  TestSigil.setProvider(provider)

  /**
   * Tool names the test agent advertises. CoreTools' names + the synthetic
   * SendSlackMessageTool so `find_capability` has a catalog entry.
   */
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

  /**
   * A synthesized AgentState id used when we want defaultProcess to emit
   * the Typing transition during streaming responses. The real dispatcher
   * supplies this via `TurnContext.currentAgentStateId`.
   */
  private val testAgentStateId: Id[Event] = Id("test-agentstate")

  private def orchestrate(message: String, currentMode: Mode = Mode.Conversation): Task[List[Signal]] = {
    val conversationId = Conversation.id("test-orchestrator-conversation")
    val userMessage = Message(
      participantId = TestUser,
      conversationId = conversationId,
      content = Vector(ResponseContent.Text(message))
    )
    val conversation = Conversation(_id = conversationId, currentMode = currentMode)
    val view = ConversationView(
      conversationId = conversationId,
      frames = Vector(ContextFrame.Text(
        content = message,
        participantId = TestUser,
        sourceEventId =
          userMessage._id
      )),
      _id = ConversationView.idFor(conversationId)
    )
    val turnContext = TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conversation,
      conversationView = view,
      turnInput = TurnInput(view),
      currentAgentStateId = Some(testAgentStateId)
    )
    makeAgent().process(turnContext, Stream.emits(List[Event](userMessage))).toList
  }

  getClass.getSimpleName should {
    "emit ToolInvoke + Message + Typing transition for a streaming respond call" in
      orchestrate("What is 2+2? Respond with just the number.").map { signals =>
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes should not be empty
        toolInvokes.head.toolName shouldBe "respond"
        toolInvokes.head.state shouldBe EventState.Active

        val messages = signals.collect { case m: Message => m }
        messages should not be empty
        messages.head.state shouldBe EventState.Active

        // Typing transition is emitted immediately before the first Message,
        // targeting the AgentState id supplied via TurnContext.
        val typingDeltas = signals.collect {
          case d: AgentStateDelta if d.activity.contains(AgentActivity.Typing) => d
        }
        typingDeltas should have size 1
        typingDeltas.head.target shouldBe testAgentStateId

        val typingIdx = signals.indexWhere {
          case d: AgentStateDelta => d.activity.contains(AgentActivity.Typing)
          case _ => false
        }
        val messageIdx = signals.indexWhere {
          case _: Message => true
          case _ => false
        }
        typingIdx should be < messageIdx

        val streamingDeltas = signals.collect {
          case d: MessageDelta if d.content.exists(!_.complete) => d.content.get
        }
        streamingDeltas.map(_.delta).mkString.trim shouldBe "4"

        val finalContentDelta = signals.collectFirst {
          case d: MessageDelta if d.content.exists(_.complete) => d.content.get
        }
        finalContentDelta.map(_.delta) shouldBe Some("4")
        finalContentDelta.map(_.complete) shouldBe Some(true)

        val toolDelta = signals.collectFirst { case d: ToolDelta => d }
        toolDelta should not be empty
        toolDelta.get.state shouldBe Some(EventState.Complete)
        toolDelta.get.input.map(_.getClass.getSimpleName) shouldBe Some("RespondInput")

        val finalMsgDelta = signals.collect { case d: MessageDelta if d.state.isDefined => d }
        finalMsgDelta.map(_.state.get) should contain(EventState.Complete)

        signals.exists {
          case d: MessageDelta => d.usage.isDefined
          case _ => false
        } shouldBe true
      }

    "persist Events and apply Deltas to the events store via SigilDB.apply" in {
      val conversationId = Conversation.id("test-persistence-conversation")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        content = Vector(ResponseContent.Text("What is 2+2? Respond with just the number."))
      )
      val conversation = Conversation(_id = conversationId)
      val view = ConversationView(
        conversationId = conversationId,
        frames = Vector(ContextFrame.Text(
          content = "What is 2+2? Respond with just the number.",
          participantId = TestUser,
          sourceEventId =
            userMessage._id
        )),
        _id = ConversationView.idFor(conversationId)
      )
      val turnContext = TurnContext(
        sigil = TestSigil,
        chain = List(TestUser),
        conversation = conversation,
        conversationView = view,
        turnInput = TurnInput(view),
        currentAgentStateId = Some(testAgentStateId)
      )

      val task = for {
        signals <- makeAgent()
          .process(turnContext, Stream.emits(List[Event](userMessage)))
          .flatMap(signal => Stream.force(TestSigil.withDB(_.apply(signal)).map(_ => Stream.emits(List(signal)))))
          .toList
        all <- TestSigil.withDB(_.events.transaction(_.list))
      } yield (signals, all.filter(_.conversationId == conversationId))

      task.map { case (_, stored) =>
        val toolInvokes = stored.collect { case t: ToolInvoke => t }
        toolInvokes should have size 1
        toolInvokes.head.toolName shouldBe "respond"
        toolInvokes.head.state shouldBe EventState.Complete
        toolInvokes.head.input.map(_.getClass.getSimpleName) shouldBe Some("RespondInput")

        val messages = stored.collect { case m: Message => m }
        messages should have size 1
        messages.head.state shouldBe EventState.Complete
        messages.head.content should not be empty
        messages.head.content.head shouldBe a[ResponseContent.Text]
        messages.head.content.head.asInstanceOf[ResponseContent.Text].text.trim shouldBe "4"
      }
    }

    "emit ToolInvoke + ModeChange for an atomic change_mode call" in
      orchestrate("I need to write a Scala function.").map { signals =>
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes should not be empty
        toolInvokes.head.toolName shouldBe "change_mode"

        signals.collect { case m: Message => m } shouldBe empty

        val toolDelta = signals.collectFirst { case d: ToolDelta => d }
        toolDelta.map(_.state) shouldBe Some(Some(EventState.Complete))

        val modeChanges = signals.collect { case m: ModeChange => m }
        modeChanges should not be empty
        modeChanges.head.mode shouldBe Mode.Coding
        modeChanges.head.state shouldBe EventState.Complete

        // Atomic tool path — no Message emitted, so no Typing transition.
        val agentStateDeltas = signals.collect { case d: AgentStateDelta => d }
        agentStateDeltas.exists(_.activity.contains(AgentActivity.Typing)) shouldBe false
      }
  }
}
