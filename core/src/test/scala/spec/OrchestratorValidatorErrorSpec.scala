package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ConversationView, Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke, ToolOutcome}
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
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
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
    val viewConvId = convId
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = viewConvId),
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
    "surface the validator error via a settling ToolDelta carrying the failure outcome" in {
      runWith(new ValidatorErrorProvider, suffix = "validator").map { signals =>
        // Sigil #265 — the orphan invoke is self-settling: one ToolDelta
        // folds state = Complete + outcome = Failure(reason, …) onto
        // the orphan invoke. The provider-error pairing path THEN
        // synthesises a `_provider_error` invoke (because the active
        // call was already cleared by `settleOrphanToolInvoke`) and
        // emits a second ToolDelta carrying the "Provider error: …"
        // reason against THAT synthetic invoke — that's the one the
        // agent reads on its next iteration.
        val errorDeltas = signals.collect {
          case d: ToolDelta if d.outcome match {
            case Some(ToolOutcome.Failure(reason, _)) => reason.contains("Provider error")
            case _                                    => false
          } => d
        }
        errorDeltas should have size 1
        val d = errorDeltas.head
        d.state shouldBe Some(EventState.Complete)
        d.outcome match {
          case Some(ToolOutcome.Failure(reason, _)) =>
            reason should include("Provider error")
            reason should include("violated schema")
            reason should include("pattern")
          case other =>
            fail(s"Expected ToolOutcome.Failure; saw $other")
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

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
