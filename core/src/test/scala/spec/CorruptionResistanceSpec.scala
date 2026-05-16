package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke, ToolResults}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal}
import sigil.tool.core.CoreTools
import sigil.tool.{ToolInput, ToolName, TypedTool}
import spice.http.HttpRequest

/**
 * Conversation-corruption resistance regression — every persisted
 * `ToolInvoke` must reach `state = Complete` AND have at least one
 * paired result event (a `MessageRole.Tool` Message OR a
 * `ToolResults` event whose `origin` points back at the invoke) by
 * the time the surrounding turn's publish pipeline finishes.
 *
 * The invariant has to hold under adversarial tool bodies — the bug
 * doc enumerates several failure shapes (sync throw at construction,
 * silent empty stream, error mid-stream). When the invariant is
 * violated the renderer's defensive fallback in
 * [[sigil.provider.Provider.renderInput]] injects `"tool failed: no
 * result emitted"` Tool-role text frames into the next turn's
 * context, which then poisons the agent's reasoning. The fix is to
 * close every emission path at the orchestrator boundary so the
 * renderer never encounters a dangling tool_call.
 */
class CorruptionResistanceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "corruption-resistance-model")

  case class AdversarialInput() extends ToolInput derives RW
  case class AnotherInput() extends ToolInput derives RW
  case class ThirdInput() extends ToolInput derives RW
  ToolInput.register(RW.static(AdversarialInput()))
  ToolInput.register(RW.static(AnotherInput()))
  ToolInput.register(RW.static(ThirdInput()))

  /** Tool whose `execute` returns an empty Stream — never emits any
    * paired result. Pre-fix, the dispatch wrapper relied on the
    * atomic-synth path to fire, but the non-atomic synth path could
    * miss this case under certain conditions. */
  private case object SilentTool extends TypedTool[AdversarialInput](
    name = ToolName("adversarial_silent"),
    description = "Adversarial tool: returns empty stream."
  ) {
  override def paginate: Boolean = false

    override protected def executeTyped(input: AdversarialInput, context: TurnContext): Stream[Event] =
      Stream.empty
  }

  /** Tool whose `execute` synchronously throws at stream
    * construction time. The framework's existing handleError wrap
    * catches this — verify it persists a paired Failure result. */
  private case object SyncThrowTool extends TypedTool[AnotherInput](
    name = ToolName("adversarial_sync_throw"),
    description = "Adversarial tool: throws at construction."
  ) {
  override def paginate: Boolean = false
    override protected def executeTyped(input: AnotherInput, context: TurnContext): Stream[Event] =
      throw new RuntimeException("adversarial: sync construction throw")
  }

  /** Tool whose `execute` returns a Stream that errors on first pull.
    * The construction-time handleError CAN'T catch this — the throw
    * happens during stream evaluation, after `Task(...)` returned a
    * Stream value successfully. Pre-fix, this could let the
    * surrounding evaluation propagate the error and leave the
    * ToolInvoke at state=Active forever. */
  private case object MidStreamErrorTool extends TypedTool[ThirdInput](
    name = ToolName("adversarial_mid_stream_error"),
    description = "Adversarial tool: errors on first stream pull."
  ) {
  override def paginate: Boolean = false
    override protected def executeTyped(input: ThirdInput, context: TurnContext): Stream[Event] =
      Stream.force[Event](Task.error(new RuntimeException("adversarial: mid-stream throw")))
  }

  private def buildRequest(convId: Id[Conversation],
                           extraTools: Vector[sigil.tool.Tool]): ConversationRequest =
    ConversationRequest(
      conversationId     = convId,
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = CoreTools.all.toVector ++ extraTools
    )

  private def seedConversation(convId: Id[Conversation]): Task[Conversation] = {
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_ => conv)
  }

  private final class SingleToolCallProvider(toolName: String, input: ToolInput) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(in: ProviderCall): Stream[ProviderEvent] = Stream.emits(List(
      ProviderEvent.ToolCallStart(CallId("adv-1"), toolName),
      ProviderEvent.ToolCallComplete(CallId("adv-1"), input),
      ProviderEvent.Done(StopReason.ToolCall)
    ))
  }

  /** Drains the orchestrator's stream the way the live runAgentLoop does
    * — `evalTap(publish)` so each signal lands in the DB as soon as
    * it's emitted. Stream-level errors after partial emission still
    * surface, but `Orchestrator.process`'s `onErrorFinalize` clean-up
    * has already published any orphan-settle signals before the
    * exception propagates. */
  private def runAndReadEvents(provider: Provider,
                               convId: Id[Conversation],
                               conv: Conversation,
                               request: ConversationRequest): Task[List[Event]] =
    for {
      _   <- Orchestrator.process(TestSigil, provider, request, conv)
               .evalTap(s => TestSigil.publish(s).handleError(_ => Task.unit))
               .drain
               .handleError(_ => Task.unit)
      evs <- TestSigil.withDB(_.events.transaction(_.list))
    } yield evs.filter(_.conversationId == convId)

  private def assertInvariantHolds(events: List[Event]): org.scalatest.Assertion = {
    val invokes = events.collect { case t: ToolInvoke => t }
    invokes should not be empty
    invokes.foreach { invoke =>
      withClue(s"ToolInvoke ${invoke._id.value} (tool=${invoke.toolName.value}) must reach state=Complete: ") {
        invoke.state shouldBe EventState.Complete
      }
      val paired = events.exists {
        case m: Message if m.role == MessageRole.Tool && m.origin.contains(invoke._id)      => true
        case r: ToolResults if r.origin.contains(invoke._id)                                => true
        case _                                                                              => false
      }
      withClue(s"ToolInvoke ${invoke._id.value} (tool=${invoke.toolName.value}) must have at least one paired result event: ") {
        paired shouldBe true
      }
    }
    succeed
  }

  "Conversation-corruption resistance invariant" should {

    "hold when a tool returns an empty Stream (silent no-op)" in {
      val convId = Conversation.id(s"silent-${rapid.Unique()}")
      for {
        conv <- seedConversation(convId)
        evs  <- runAndReadEvents(
                  new SingleToolCallProvider(SilentTool.name.value, AdversarialInput()),
                  convId, conv, buildRequest(convId, Vector(SilentTool))
                )
      } yield assertInvariantHolds(evs)
    }

    "hold when a tool throws synchronously at stream construction" in {
      val convId = Conversation.id(s"sync-throw-${rapid.Unique()}")
      for {
        conv <- seedConversation(convId)
        evs  <- runAndReadEvents(
                  new SingleToolCallProvider(SyncThrowTool.name.value, AnotherInput()),
                  convId, conv, buildRequest(convId, Vector(SyncThrowTool))
                )
      } yield assertInvariantHolds(evs)
    }

    "hold when a tool's Stream errors on first pull (mid-stream throw)" in {
      val convId = Conversation.id(s"mid-stream-${rapid.Unique()}")
      for {
        conv <- seedConversation(convId)
        evs  <- runAndReadEvents(
                  new SingleToolCallProvider(MidStreamErrorTool.name.value, ThirdInput()),
                  convId, conv, buildRequest(convId, Vector(MidStreamErrorTool))
                )
      } yield assertInvariantHolds(evs)
    }

    "hold when the provider stream aborts between ToolCallStart and ToolCallComplete" in {
      // Reproduces the ReadTimeoutException / network-drop case
      // documented in the bug — the wire delivers a ToolCallStart
      // chunk but the connection drops before the Complete chunk
      // arrives. Without this guard, the in-flight ToolInvoke is
      // stuck at state=Active forever and the next turn's renderer
      // synthesises a `"tool failed: no result emitted"` Tool-role
      // text frame that poisons the agent's context.
      val convId = Conversation.id(s"provider-abort-${rapid.Unique()}")
      val abortingProvider = new Provider {
        override def `type`: ProviderType = ProviderType.LlamaCpp
        override def models: List[Model] = Nil
        override protected def sigil: _root_.sigil.Sigil = TestSigil
        override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
          Task.error(new UnsupportedOperationException("no wire"))
        override def call(in: ProviderCall): Stream[ProviderEvent] =
          // Emit the start chunk, then defer an error to the next pull
          // via evalMap so the framework actually sees the start before
          // the abort fires (Stream.force(Task.error) errors at init).
          Stream.emit[ProviderEvent](ProviderEvent.ToolCallStart(CallId("aborted-call"), "change_mode")) ++
            Stream.emit[Unit](()).evalMap(_ => Task.error[ProviderEvent](new java.io.IOException("read timeout")))
      }
      for {
        conv <- seedConversation(convId)
        evs  <- runAndReadEvents(abortingProvider, convId, conv, buildRequest(convId, Vector.empty))
      } yield assertInvariantHolds(evs)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
