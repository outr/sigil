package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{ContextFrame, Conversation, ConversationView, Topic, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, ModeChange, TopicChange, TopicChangeKind, ToolInvoke}
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant}
import sigil.provider.{GenerationSettings, Instructions, Mode, Provider}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, MessageDelta, Signal, StateDelta, ToolDelta}
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

  /** Wire the spec's provider into TestSigil so `providerFor` returns it. */
  TestSigil.setProvider(provider)

  /** Tool names the test agent advertises. CoreTools' default roster
    * plus the synthetic SendSlackMessageTool and the non-core SleepTool
    * so orchestrator tests exercising sleep-timing have it available. */
  protected def toolNames: List[sigil.tool.ToolName] =
    CoreTools.coreToolNames ++ List(SendSlackMessageTool.schema.name, sigil.tool.util.SleepTool.schema.name)

  protected def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = toolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0))
    )

  /** A synthesized AgentState id used when we want defaultProcess to emit
    * the Typing transition during streaming responses. The real dispatcher
    * supplies this via `TurnContext.currentAgentStateId`. */
  private val testAgentStateId: Id[Event] = Id("test-agentstate")

  /**
   * Run a single turn for a freshly-bootstrapped conversation — a Topic with
   * the default bootstrap label is persisted, so the orchestrator's topic
   * resolution has a real record to compare against. Returns the signals the
   * orchestrator produced AND the initial Topic (callers assert against its
   * id / label). Used by the live-LLM topic-change coverage test below.
   */
  private def orchestrateFresh(message: String, suffix: String): Task[(List[Signal], Topic)] = {
    val conversationId = Conversation.id(s"test-topic-trigger-$suffix-${rapid.Unique()}")
    val topic = Topic(
      conversationId = conversationId,
      label = Topic.DefaultLabel,
      summary = Topic.DefaultSummary,
      createdBy = TestUser
    )
    val conv = Conversation(
      topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
      _id = conversationId
    )
    val userMessage = Message(
      participantId = TestUser,
      conversationId = conversationId,
      topicId = topic._id,
      content = Vector(ResponseContent.Text(message))
    )
    val view = ConversationView(
      conversationId = conversationId,
      frames = Vector(ContextFrame.Text(
        content = message,
        participantId = TestUser,
        sourceEventId = userMessage._id
      )),
      _id = ConversationView.idFor(conversationId)
    )
    val turnContext = TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      conversationView = view,
      turnInput = TurnInput(view),
      currentAgentStateId = Some(testAgentStateId)
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(topic)))
      signals <- makeAgent().process(turnContext, Stream.emits(List[Event](userMessage))).toList
    } yield (signals, topic)
  }

  /**
   * Same as [[orchestrateFresh]] but seeds the conversation with a Topic
   * already past the bootstrap label — the label is exactly `seedLabel`,
   * `labelLocked = false`. Used for the "iterative rename" and "hard
   * switch" live-LLM tests: by starting on an established label we
   * isolate the LLM's judgement about whether the user's next message
   * refines the current subject (Rename) or moves to a new one (Switch).
   */
  private def orchestrateAfterSeed(seedLabel: String,
                                   userMessage: String,
                                   suffix: String): Task[(List[Signal], Topic)] = {
    val conversationId = Conversation.id(s"test-topic-seeded-$suffix-${rapid.Unique()}")
    val topic = Topic(
      conversationId = conversationId,
      label = seedLabel,
      summary = s"Seed summary for $seedLabel.",
      createdBy = TestUser
    )
    val conv = Conversation(
      topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
      _id = conversationId
    )
    val userEv = Message(
      participantId = TestUser,
      conversationId = conversationId,
      topicId = topic._id,
      content = Vector(ResponseContent.Text(userMessage))
    )
    val view = ConversationView(
      conversationId = conversationId,
      frames = Vector(ContextFrame.Text(
        content = userMessage,
        participantId = TestUser,
        sourceEventId = userEv._id
      )),
      _id = ConversationView.idFor(conversationId)
    )
    val turnContext = TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      conversationView = view,
      turnInput = TurnInput(view),
      currentAgentStateId = Some(testAgentStateId)
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(topic)))
      signals <- makeAgent().process(turnContext, Stream.emits(List[Event](userEv))).toList
    } yield (signals, topic)
  }

  private def orchestrate(message: String, currentMode: Mode = Mode.Conversation): Task[List[Signal]] = {
    val conversationId = Conversation.id("test-orchestrator-conversation")
    val userMessage = Message(
      participantId = TestUser,
      conversationId = conversationId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(message))
    )
    val conversation = Conversation(topics = TestTopicStack, _id = conversationId, currentMode = currentMode)
    val view = ConversationView(
      conversationId = conversationId,
      frames = Vector(ContextFrame.Text(
        content = message,
        participantId = TestUser,
        sourceEventId = userMessage._id
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
    "emit ToolInvoke + Message + Typing transition for a streaming respond call" in {
      orchestrate("What is 2+2? Respond with just the number.").map { signals =>
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes should not be empty
        toolInvokes.head.toolName shouldBe sigil.tool.ToolName("respond")
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
    }

    "persist Events and apply Deltas to the events store via SigilDB.apply" in {
      val conversationId = Conversation.id("test-persistence-conversation")
      val userMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("What is 2+2? Respond with just the number."))
      )
      val conversation = Conversation(topics = TestTopicStack, _id = conversationId)
      val view = ConversationView(
        conversationId = conversationId,
        frames = Vector(ContextFrame.Text(
          content = "What is 2+2? Respond with just the number.",
          participantId = TestUser,
          sourceEventId = userMessage._id
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
        toolInvokes.head.toolName shouldBe sigil.tool.ToolName("respond")
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

    "emit ToolInvoke + ModeChange for an atomic change_mode call" in {
      orchestrate("I need to write a Scala function.").map { signals =>
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes should not be empty
        toolInvokes.head.toolName shouldBe sigil.tool.ToolName("change_mode")

        signals.collect { case m: Message => m } shouldBe empty

        val toolDelta = signals.collectFirst { case d: ToolDelta => d }
        toolDelta.map(_.state) shouldBe Some(Some(EventState.Complete))

        val modeChanges = signals.collect { case m: ModeChange => m }
        modeChanges should not be empty
        modeChanges.head.mode shouldBe Mode.Coding
        // Lifecycle: ModeChange is emitted as the `Active` pulse, then the
        // orchestrator's `executeAtomic` wrapper emits a `StateDelta(Complete)`
        // targeting its `_id` right after to settle it. Both must be
        // present in the signal stream — that's the Active → Complete
        // invariant in action.
        modeChanges.head.state shouldBe EventState.Active
        val modeChangeId = modeChanges.head._id
        val settle = signals.collectFirst {
          case sd: StateDelta if sd.target == modeChangeId && sd.state == EventState.Complete => sd
        }
        settle should not be empty

        // Sequencing: the settle must follow the pulse (Active pulse is
        // emitted first, settle after).
        val pulseIdx = signals.indexWhere {
          case mc: ModeChange if mc._id == modeChangeId => true
          case _ => false
        }
        val settleIdx = signals.indexWhere {
          case sd: StateDelta if sd.target == modeChangeId && sd.state == EventState.Complete => true
          case _ => false
        }
        pulseIdx should be < settleIdx

        // Atomic tool path — no Message emitted, so no Typing transition.
        val agentStateDeltas = signals.collect { case d: AgentStateDelta => d }
        agentStateDeltas.exists(_.activity.contains(AgentActivity.Typing)) shouldBe false
      }
    }

    // Live-LLM coverage of the topic-trigger path. The system prompt shows
    // `Current topic: "New Conversation"` (the bootstrap label). With the
    // categorical `topicChangeType` schema the RespondTool description
    // instructs the LLM to pick `Change` on bootstrap (since the default
    // label is being replaced with a real subject) — probing showed this
    // is reliable. We assert the orchestrator fires a Switch with a label
    // other than the default.
    "fire a TopicChange(Switch) when the LLM gets a clear subject on a fresh conversation" in {
      val prompt =
        "I want to talk about the history of the Roman Empire. Give me a brief two-sentence overview " +
          "of its founding."
      orchestrateFresh(prompt, suffix = "fresh-subject").map { case (signals, initialTopic) =>
        // The LLM's respond should have populated `topic` with something
        // other than the default bootstrap label.
        val toolCompletes = signals.collect { case d: ToolDelta => d }
        val respondInput = toolCompletes.flatMap(_.input.toList).collectFirst {
          case r: sigil.tool.model.RespondInput => r
        }
        respondInput should not be empty
        respondInput.get.topicLabel should not equal Topic.DefaultLabel
        respondInput.get.topicLabel.trim should not be empty
        respondInput.get.topicSummary.trim should not be empty

        // The orchestrator should have materialized the shift as a
        // TopicChange — either Rename (medium confidence / no confidence
        // fallback) or Switch (high confidence). Both are valid outcomes
        // here; the key claim is that SOME TopicChange was emitted.
        val topicChanges = signals.collect { case tc: TopicChange => tc }
        topicChanges should have size 1
        val tc = topicChanges.head
        tc.newLabel shouldBe respondInput.get.topicLabel

        // Each TopicChange is Active pulse → Complete settle via StateDelta
        // (orchestrator's own settle, not executeAtomic's).
        val tcSettle = signals.collectFirst {
          case sd: StateDelta if sd.target == tc._id && sd.state == EventState.Complete => sd
        }
        tcSettle should not be empty

        // The pulse must precede the settle.
        val pulseIdx = signals.indexWhere {
          case seen: TopicChange if seen._id == tc._id => true
          case _ => false
        }
        val settleIdx = signals.indexWhere {
          case sd: StateDelta if sd.target == tc._id => true
          case _ => false
        }
        pulseIdx should be < settleIdx

        // Bootstrap = Switch: LLM correctly picked `Change` (verified by
        // probe). The event is a Switch pointing at a fresh Topic.
        val switchKind = tc.kind match {
          case s: TopicChangeKind.Switch => s
          case other => fail(s"expected Switch on bootstrap, got: $other")
        }
        switchKind.previousTopicId shouldBe initialTopic._id
        tc.topicId should not be initialTopic._id
      }
    }

    // Iterative-refinement path: user refines an already-established
    // subject. The two-step classifier can legitimately produce NoChange
    // (if it judges the proposal as the same subject with no improvement),
    // Refine (same subject with a sharper label — adopt it), or even
    // Switch-to-prior if the refinement matches a prior label exactly.
    // All three are defensible judgments — the probe data showed this is
    // a subjective edge. What we assert: IF a TopicChange fires, it's
    // structurally sound and its label reflects the narrowed subject.
    "handle an iterative-refinement message coherently (NoChange or Rename, both defensible)" in {
      orchestrateAfterSeed(
        seedLabel = "Python Programming",
        userMessage =
          "Specifically, I want to understand how Python's Global Interpreter Lock (GIL) affects " +
            "concurrent threads. Answer in ONE short sentence only.",
        suffix = "rename-refine"
      ).map { case (signals, seeded) =>
        val topicChanges = signals.collect { case tc: TopicChange => tc }
        topicChanges.foreach { tc =>
          val labelLower = tc.newLabel.toLowerCase
          val mentionsRefinement =
            labelLower.contains("gil") ||
              labelLower.contains("interpreter lock") ||
              labelLower.contains("thread") ||
              labelLower.contains("concurren") ||
              labelLower.contains("python")
          withClue(s"LLM produced topic label: '${tc.newLabel}' — expected to reflect the GIL refinement.") {
            mentionsRefinement shouldBe true
          }
          // Whichever kind fires, framework invariants hold.
          tc.kind match {
            case TopicChangeKind.Rename(prev) =>
              prev shouldBe "Python Programming"
              tc.topicId shouldBe seeded._id
            case TopicChangeKind.Switch(prev) =>
              prev shouldBe seeded._id
              tc.topicId should not be seeded._id
          }
        }
        topicChanges.size should be <= 1
      }
    }

    // Hard-switch path: user explicitly changes subject. Probe results
    // show Qwen picks `Change`, which the orchestrator maps to a
    // `Switch` — fresh Topic with the new label.
    "fire a TopicChange(Switch) when the LLM detects an abrupt subject change" in {
      orchestrateAfterSeed(
        seedLabel = "Roman Empire History",
        userMessage =
          "Let's switch topics completely. Forget the Romans — in ONE short sentence only, " +
            "tell me what TypeScript generics are.",
        suffix = "hard-switch"
      ).map { case (signals, seeded) =>
        // Extract what the LLM's respond call produced — CI failures
        // otherwise can't tell if this was the short-circuit path
        // (topicLabel == current) or a classifier NoChange verdict.
        val respondInput = signals.collect { case d: ToolDelta => d }
          .flatMap(_.input.toList)
          .collectFirst { case r: sigil.tool.model.RespondInput => r }
        val topicChanges = signals.collect { case tc: TopicChange => tc }
        val diagnostic =
          s"""Diagnostic:
             |  seed label           = 'Roman Empire History'
             |  respond.topicLabel   = ${respondInput.map(r => s"'${r.topicLabel}'").getOrElse("(missing)")}
             |  respond.topicSummary = ${respondInput.map(r => s"'${r.topicSummary}'").getOrElse("(missing)")}
             |  TopicChange count    = ${topicChanges.size}
             |  TopicChange labels   = ${topicChanges.map(tc => s"'${tc.newLabel}'").mkString(", ")}
             |""".stripMargin
        withClue(diagnostic) {
          topicChanges should have size 1
        }
        val tc = topicChanges.head
        val labelLower = tc.newLabel.toLowerCase
        // The new label should reflect the NEW subject (TypeScript /
        // generics) and NOT the old subject (Rome / Roman Empire).
        val mentionsNewSubject =
          labelLower.contains("typescript") ||
            labelLower.contains("generic") ||
            labelLower.contains("type")
        val mentionsOldSubject =
          labelLower.contains("roman") || labelLower.contains("empire")
        withClue(s"LLM produced topic label: '${tc.newLabel}' — expected new subject, not the seed.") {
          mentionsNewSubject shouldBe true
          mentionsOldSubject shouldBe false
        }
        // Hard switch = Switch: fresh Topic, seed topic id preserved as
        // previousTopicId on the event.
        val switchKind = tc.kind match {
          case s: TopicChangeKind.Switch => s
          case other => fail(s"expected Switch on hard subject change, got: $other")
        }
        switchKind.previousTopicId shouldBe seeded._id
        tc.topicId should not be seeded._id
      }
    }
  }
}
