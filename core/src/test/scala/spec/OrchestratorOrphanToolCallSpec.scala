package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ConversationView, Conversation, TurnInput}
import sigil.db.Model
import sigil.event.ToolInvoke
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.core.RespondTool
import spice.http.HttpRequest

/**
 * Regression for bug #33 — when a provider stream ends between
 * `ToolCallStart` and `ToolCallComplete` (token-budget cutoff,
 * network drop, mid-args 5xx), the in-flight `ToolInvoke` was left
 * at `state=Active` forever because no terminal `ToolDelta` was
 * emitted. The orchestrator now settles any orphan in the `Done` /
 * `Error` arms before terminating the stream.
 */
class OrchestratorOrphanToolCallSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /** Provider that emits a `ToolCallStart` then immediately `Done`,
    * simulating a stream that died mid-tool-call. */
  private class OrphanedDoneProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("orphan-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Same shape but ends with `Error` instead of `Done`. */
  private class OrphanedErrorProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("orphan-error-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.Error("provider connection dropped")
      ))
    }
  }

  private def runWith(provider: Provider, suffix: String): Task[List[sigil.signal.Signal]] = {
    val convId = Conversation.id(s"orphan-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    val request = ConversationRequest(
      conversationId = convId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(view),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      previousTopics = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain = List(TestUser, TestAgent),
      tools = Vector(RespondTool)
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request).toList
    } yield signals
  }

  "Orchestrator (bug #33)" should {
    "emit a terminal ToolDelta when the provider stream ends mid-tool-call (Done arm)" in {
      runWith(new OrphanedDoneProvider, suffix = "done").map { signals =>
        val invoke = signals.collectFirst { case t: ToolInvoke => t }.getOrElse(
          fail(s"Expected a ToolInvoke; saw ${signals.map(_.getClass.getSimpleName).mkString(", ")}")
        )
        invoke.state shouldBe EventState.Active // emitted Active first

        val terminalDelta = signals.collect { case d: ToolDelta => d }.find(_.target == invoke._id)
        terminalDelta should not be empty
        terminalDelta.flatMap(_.state) shouldBe Some(EventState.Complete)
      }
    }

    "emit a terminal ToolDelta when the provider stream ends mid-tool-call (Error arm)" in {
      runWith(new OrphanedErrorProvider, suffix = "error").map { signals =>
        val invoke = signals.collectFirst { case t: ToolInvoke => t }.getOrElse(
          fail("Expected a ToolInvoke")
        )
        val terminalDelta = signals.collect { case d: ToolDelta => d }.find(_.target == invoke._id)
        terminalDelta should not be empty
        terminalDelta.flatMap(_.state) shouldBe Some(EventState.Complete)
      }
    }
  }
}
