package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ConversationView, Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageRole, MessageVisibility, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType
}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.core.RespondTool
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

/**
 * Regression for bug #50 — when the post-decode validator (or any
 * provider-side error) rejects tool args, the orchestrator used to
 * silently drop the error message and emit only an orphan
 * `ToolDelta(state=Complete)`. The agent's next turn had no signal
 * anything went wrong and entered a silent retry loop ending in
 * the bug #46 placeholder.
 *
 * The orchestrator now surfaces every `ProviderEvent.Error` as a
 * Tool-role `MessageVisibility.Agents` `Message` carrying the
 * error text — agent reads it via the standard trigger-filter
 * frame, retries with corrected args.
 */
class OrchestratorValidatorErrorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /** Provider that emits a `ToolCallStart` then immediately a
    * `ProviderEvent.Error` simulating a post-decode validator
    * rejection (the args matched the wire schema but failed a
    * `pattern` constraint, etc.). */
  private class ValidatorErrorProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("validator-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.Error(
          "Args for tool find_capability violated schema constraints: " +
            "keywords does not match pattern ^[a-z0-9]+( [a-z0-9]+)*$"
        )
      ))
    }
  }

  private def runWith(provider: Provider, suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"validator-error-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(view),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = Vector(RespondTool)
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Orchestrator (bug #50)" should {
    "surface a Tool-role Message carrying the validator error text" in {
      runWith(new ValidatorErrorProvider, suffix = "validator").map { signals =>
        // Sanity — the orphan ToolInvoke gets a settled ToolDelta so
        // the chip resolves rather than hanging at "input pending".
        val invoke = signals.collectFirst { case t: ToolInvoke => t }
          .getOrElse(fail("Expected a ToolInvoke; saw none"))
        val terminalDelta = signals.collect { case d: ToolDelta => d }.find(_.target == invoke._id)
        terminalDelta.flatMap(_.state) shouldBe Some(EventState.Complete)

        // The actual fix — the validator error reaches the agent as
        // a Tool-role Message instead of being dropped.
        val errorMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        errorMessages should have size 1
        val msg = errorMessages.head
        msg.visibility shouldBe MessageVisibility.Agents
        msg.state shouldBe EventState.Complete
        msg.content.headOption match {
          case Some(ResponseContent.Text(text)) =>
            text should include("Provider error")
            text should include("violated schema")
            text should include("pattern")
          case other =>
            fail(s"Expected Text content; saw $other")
        }
      }
    }
  }

  "Orchestrator (bug #51)" should {
    "carry the error text on the orphan-settle ToolDelta so client chips can render it" in {
      runWith(new ValidatorErrorProvider, suffix = "chip-error").map { signals =>
        val invoke = signals.collectFirst { case t: ToolInvoke => t }
          .getOrElse(fail("Expected a ToolInvoke; saw none"))
        val terminalDelta = signals.collect { case d: ToolDelta => d }.find(_.target == invoke._id)
          .getOrElse(fail("Expected a settled ToolDelta for the in-flight invoke"))
        // The orphan-settle now carries `error` so the UI knows this
        // chip closed with a validator failure rather than still being
        // mid-flight.
        terminalDelta.error shouldBe defined
        terminalDelta.error.get should include("violated schema")
        terminalDelta.input shouldBe None
        terminalDelta.state shouldBe Some(EventState.Complete)
      }
    }
  }
}
