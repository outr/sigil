package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, ModeChangedEvent, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{GenerationSettings, Instructions, Mode, Provider, ProviderRequest}
import sigil.signal.{EventState, MessageDelta, Signal, ToolDelta}
import sigil.tool.{Tool, ToolInput}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

/**
 * Drives the orchestrator through a live LLM provider and asserts on the
 * resulting `Signal` stream — the external vocabulary sigil consumers see.
 * Extend by providing `provider` and `modelId`.
 *
 * Intentionally independent of [[AbstractProviderSpec]] — the provider-level
 * and orchestrator-level tests assert at different layers (ProviderEvent vs
 * Signal) and shouldn't be run as one combined suite.
 */
trait AbstractOrchestratorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  // Per-suite DB path so each forked JVM gets its own RocksDB instance.
  TestSigil.initFor(getClass.getSimpleName)

  protected def provider: Task[Provider]

  protected def modelId: Id[Model]

  protected def tools: Vector[Tool[? <: ToolInput]] = CoreTools(TestSigil).all

  private def orchestrate(message: String, currentMode: Mode = Mode.Conversation): Task[List[Signal]] =
    provider.flatMap { p =>
      val conversationId = Conversation.id("test-orchestrator-conversation")
      val request = ProviderRequest(
        conversationId = conversationId,
        modelId = modelId,
        instructions = Instructions(),
        context = sigil.conversation.ConversationContext(
          events = Vector(
            Message(
              participantId = TestUser,
              conversationId = conversationId,
              content = Vector(ResponseContent.Text(message))
            )
          )
        ),
        currentMode = currentMode,
        generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0)),
        tools = tools,
        chain = List(TestUser)
      )
      Orchestrator.process(TestSigil, p, request).toList
    }

  getClass.getSimpleName should {
    "emit ToolInvoke + Message + Deltas for a streaming respond call" in {
      orchestrate("What is 2+2? Respond with just the number.").map { signals =>
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes should not be empty
        toolInvokes.head.toolName shouldBe "respond"
        toolInvokes.head.state shouldBe EventState.Active

        val messages = signals.collect { case m: Message => m }
        messages should not be empty
        messages.head.state shouldBe EventState.Active

        // Streaming partial deltas (complete=false) carry the chunked text for UX.
        val streamingDeltas = signals.collect {
          case d: MessageDelta if d.content.exists(!_.complete) => d.content.get
        }
        streamingDeltas.map(_.delta).mkString.trim shouldBe "4"

        // A final ContentDelta with complete=true closes the block; carries the full block text
        // so a DB applier can append a complete ResponseContent.
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

    "persist Events and apply Deltas to the events store via SigilDB.applySignal" in {
      val conversationId = Conversation.id("test-persistence-conversation")
      val task = for {
        provider <- provider
        request = ProviderRequest(
          conversationId = conversationId,
          modelId = modelId,
          instructions = sigil.provider.Instructions(),
          context = sigil.conversation.ConversationContext(
            events = Vector(
              Message(
                participantId = TestUser,
                conversationId = conversationId,
                content = Vector(ResponseContent.Text("What is 2+2? Respond with just the number."))
              )
            )
          ),
          currentMode = Mode.Conversation,
          generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0)),
          tools = tools,
          chain = List(TestUser)
        )
        signals <- Orchestrator
          .process(TestSigil, provider, request)
          .flatMap { signal =>
            Stream.force(TestSigil.withDB(_.apply(signal)).map(_ => Stream.emits(List(signal))))
          }
          .toList
        // Query everything in the events store; filter to this conversation in memory.
        // (A model-level conversationId field for indexed queries would let us filter at the store.)
        all <- TestSigil.withDB(_.events.transaction(_.list))
      } yield (signals, all.filter(_.conversationId == conversationId))

      task.map { case (_, stored) =>
        // The user's input Message went into the request but isn't persisted by the orchestrator —
        // only Events the orchestrator emits show up. Expect ToolInvoke + Message (the agent's response).
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

    "emit ToolInvoke + ModeChangedEvent for an atomic change_mode call" in {
      orchestrate("I need to write a Scala function.").map { signals =>
        val toolInvokes = signals.collect { case t: ToolInvoke => t }
        toolInvokes should not be empty
        toolInvokes.head.toolName shouldBe "change_mode"

        signals.collect { case m: Message => m } shouldBe empty

        val toolDelta = signals.collectFirst { case d: ToolDelta => d }
        toolDelta.map(_.state) shouldBe Some(Some(EventState.Complete))

        val modeChanges = signals.collect { case m: ModeChangedEvent => m }
        modeChanges should not be empty
        modeChanges.head.mode shouldBe Mode.Coding
        modeChanges.head.state shouldBe EventState.Complete
      }
    }
  }
}
