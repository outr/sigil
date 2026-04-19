package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, ModeChangedEvent, ToolInvoke}
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

  protected def tools: Vector[Tool[? <: ToolInput]] = CoreTools(TestSigil.toolManager).all

  private def orchestrate(message: String, currentMode: Mode = Mode.Conversation): Task[List[Signal]] =
    provider.flatMap { p =>
      val conversationId = Conversation.id("test-orchestrator-conversation")
      val request = ProviderRequest(
        conversationId = conversationId,
        modelId = modelId,
        instructions = Instructions(),
        events = Vector(
          Message(
            participantId = TestUser,
            conversationId = conversationId,
            content = Vector(ResponseContent.Text(message))
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

        val contentDeltas = signals.collect {
          case d: MessageDelta if d.content.isDefined => d.content.get
        }
        contentDeltas.map(_.delta).mkString.trim shouldBe "4"

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
