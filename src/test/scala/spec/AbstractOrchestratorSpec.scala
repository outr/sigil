package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationContext}
import sigil.db.Model
import sigil.event.{AgentState, Event, Message, ModeChange, ToolInvoke}
import sigil.participant.{AgentParticipant, AgentParticipantId}
import sigil.provider.{GenerationSettings, Instructions, Mode, Provider}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, MessageDelta, Signal, ToolDelta}
import sigil.tool.{Tool, ToolInput}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

/**
 * Drives an [[AgentParticipant]] through a live LLM provider and asserts on
 * the resulting `Signal` stream — the external vocabulary sigil consumers
 * see, including the agent's own AgentState lifecycle.
 *
 * Extend by providing `provider` and `modelId`.
 */
trait AbstractOrchestratorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  protected def provider: Task[Provider]

  protected def modelId: Id[Model]

  protected def tools: Vector[Tool[? <: ToolInput]] = CoreTools(TestSigil).all

  protected def makeAgent(mode: Mode = Mode.Conversation): AgentParticipant = {
    val spec = this
    new AgentParticipant {
      override val id: AgentParticipantId = TestAgent
      override val modelId: Id[Model] = spec.modelId
      override def provider: Task[Provider] = spec.provider
      override def tools: Vector[Tool[? <: ToolInput]] = spec.tools
      override def instructions: Instructions = Instructions()
      override def generationSettings: GenerationSettings =
        GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0))
      override def currentMode: Mode = mode
    }
  }

  private def orchestrate(message: String, currentMode: Mode = Mode.Conversation): Task[List[Signal]] = {
    val conversationId = Conversation.id("test-orchestrator-conversation")
    val userMessage = Message(
      participantId = TestUser,
      conversationId = conversationId,
      content = Vector(ResponseContent.Text(message))
    )
    val conversation: Conversation = new Conversation {
      override val id = conversationId
    }
    val conversationContext = ConversationContext(events = Vector(userMessage))
    val turnContext = TurnContext(TestSigil, List(TestUser), conversation, conversationContext)
    makeAgent(currentMode).process(turnContext, List(userMessage)).toList
  }

  getClass.getSimpleName should {
    "emit AgentState lifecycle around a streaming respond call" in {
      orchestrate("What is 2+2? Respond with just the number.").map { signals =>
        val agentStates = signals.collect { case a: AgentState => a }
        agentStates should have size 1
        agentStates.head.activity shouldBe AgentActivity.Thinking
        agentStates.head.state shouldBe EventState.Active

        val agentStateDeltas = signals.collect { case d: AgentStateDelta => d }
        agentStateDeltas should not be empty

        val typing = agentStateDeltas.find(_.activity.contains(AgentActivity.Typing))
        typing should not be empty

        val terminal = agentStateDeltas.last
        terminal.activity shouldBe Some(AgentActivity.Idle)
        terminal.state shouldBe Some(EventState.Complete)

        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes should not be empty
        toolInvokes.head.toolName shouldBe "respond"
        toolInvokes.head.state shouldBe EventState.Active

        val messages = signals.collect { case m: Message => m }
        messages should not be empty
        messages.head.state shouldBe EventState.Active

        // Typing transition is emitted immediately before the first Message
        // — it's the signal to subscribers that the agent has moved from
        // reasoning to producing content, and it precedes the content so
        // UIs can switch indicators before any streamed text appears.
        val typingIdx = signals.indexWhere {
          case d: AgentStateDelta => d.activity.contains(AgentActivity.Typing)
          case _ => false
        }
        val messageIdx = signals.indexWhere {
          case _: Message => true
          case _ => false
        }
        typingIdx should be >= 0
        messageIdx should be >= 0
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
    }

    "persist Events and apply Deltas to the events store via SigilDB.apply" in {
      val conversationId = Conversation.id("test-persistence-conversation")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        content = Vector(ResponseContent.Text("What is 2+2? Respond with just the number."))
      )
      val conversation: Conversation = new Conversation {
        override val id = conversationId
      }
      val conversationContext = ConversationContext(events = Vector(userMessage))
      val turnContext = TurnContext(TestSigil, List(TestUser), conversation, conversationContext)

      val task = for {
        signals <- makeAgent()
          .process(turnContext, List(userMessage))
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

        val agentStates = stored.collect { case a: AgentState => a }
        agentStates should have size 1
        agentStates.head.activity shouldBe AgentActivity.Idle
        agentStates.head.state shouldBe EventState.Complete
      }
    }

    "emit ToolInvoke + ModeChange for an atomic change_mode call" in {
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
        modeChanges.head.state shouldBe EventState.Active

        val agentStates = signals.collect { case a: AgentState => a }
        agentStates should have size 1
        agentStates.head.activity shouldBe AgentActivity.Thinking

        val agentStateDeltas = signals.collect { case d: AgentStateDelta => d }
        // Atomic tool path — no Message emitted, so no Typing transition.
        agentStateDeltas.exists(_.activity.contains(AgentActivity.Typing)) shouldBe false
        agentStateDeltas.last.activity shouldBe Some(AgentActivity.Idle)
        agentStateDeltas.last.state shouldBe Some(EventState.Complete)
      }
    }
  }
}
