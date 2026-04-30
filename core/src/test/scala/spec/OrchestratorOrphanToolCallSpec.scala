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

  /** Provider that emits a `ToolCallStart` then THROWS — the
    * stream never reaches `Done` / `Error`. Simulates HTTP layer
    * raising mid-stream (e.g., context-overflow 400, dropped
    * connection, fiber cancel). The orchestrator's `onErrorFinalize`
    * must publish a synthetic terminal `ToolDelta` for the orphan
    * before propagating, otherwise clients see a forever-Active
    * `ToolInvoke`. This is bug #37 — the residual case bug #33's
    * fix didn't cover. */
  private class OrphanedThrowingProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("orphan-throw-call")
      // Emit an index sequence then throw on the second eval —
      // that makes the error surface at stepTask level (where
      // onErrorFinalize taps in), unlike `Stream.force(Task.error)`
      // which fails at Pull-materialization time before the
      // step-level handler is wrapped.
      Stream.emits(List(0, 1)).evalMap { idx =>
        if (idx == 0) Task.pure[ProviderEvent](
          ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value)
        )
        else Task.error[ProviderEvent](new RuntimeException("simulated mid-stream failure (bug #37)"))
      }
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

  "Orchestrator (bug #37)" should {
    "publish a terminal ToolDelta when the provider stream throws mid-call (no Done/Error arm reached)" in {
      val convId = Conversation.id("orphan-throw")
      val conv   = Conversation(topics = TestTopicStack, _id = convId)
      val view   = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
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

      // Subscribe to the host's signal stream so the directly-published
      // orphan ToolDelta (published by the orchestrator's
      // `onErrorFinalize`, NOT emitted into the stream — the stream is
      // dead at that point) is observable. takeWhile flips false on
      // the cleanup so the subscription releases naturally.
      @volatile var running = true
      val recorded = new java.util.concurrent.ConcurrentLinkedQueue[sigil.signal.Signal]()
      TestSigil.signals
        .takeWhile(_ => running)
        .evalMap(s => Task { recorded.add(s); () })
        .drain
        .startUnit()
      Thread.sleep(100)

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        // Drain the stream. The mid-stream throw will surface as a
        // failed Task; we attempt it and assert the failure
        // propagates AND the orphan settle was published.
        attempted <- Orchestrator.process(TestSigil, new OrphanedThrowingProvider, request).toList.attempt
        _          = Thread.sleep(150)
        _          = running = false
      } yield {
        attempted.isFailure shouldBe true
        attempted.failed.get.getMessage should include("simulated mid-stream")

        import scala.jdk.CollectionConverters.*
        val all = recorded.iterator().asScala.toList
        // The ToolInvoke landed before the throw (emitted from the
        // stream prefix and consumed before the failure). The
        // terminal ToolDelta — published directly by the
        // orchestrator's onErrorFinalize — must be present.
        val orphanDeltas = all.collect { case d: ToolDelta => d }
          .filter(_.state.contains(EventState.Complete))
        orphanDeltas should not be empty
      }
    }
  }
}
