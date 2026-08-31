package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, ContextFrame, FrameBuilder, ToolCallState}
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.provider.{Provider, ProviderMessage}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent

/**
 * Regression for sigil bug #174 — an orphan tool result (a Tool-role
 * event whose parent call is not in the current request's frames) must
 * never reach the wire as a `ProviderMessage.ToolResult`. When it did,
 * OpenAI / DeepInfra / etc. 400'd the request with "No tool call found
 * for function call output with call_id ...".
 *
 * Driven end-to-end through the real projection: events go through
 * `FrameBuilder`, the resulting frames through `Provider.renderFrames`.
 * A genuine orphan degrades to an agents-only Text frame and emits no
 * wire result; a settled synthetic diagnostic's paired message is NOT
 * an orphan and must not produce the fallback at all.
 */
class OrphanToolResultRenderSpec extends AnyWordSpec with Matchers {

  /**
   * Stub Provider exposing the protected `renderFrames` for direct
   * testing. The Provider trait declares `renderFrames` as
   * `protected[provider]`, so this stub lives in the same package
   * spec-side via a thin shim.
   */
  private object Probe extends Provider {
    override def `type` = _root_.sigil.provider.ProviderType.LlamaCpp
    override def models = Nil
    override protected def sigil = TestSigil
    override def httpRequestFor(input: _root_.sigil.provider.ProviderCall) =
      rapid.Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: _root_.sigil.provider.ProviderCall) = rapid.Stream.empty

    def renderFor(frames: Vector[ContextFrame],
                  agentId: _root_.sigil.participant.ParticipantId): Vector[ProviderMessage] =
      renderFrames(frames, Some(agentId))
  }

  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("orphan-render-conv")

  "Bug #174 — orphan ToolResult guard" should {

    "drop a ToolResult whose matching ToolCall isn't in the request" in {
      // Build the frames the REAL projection produces for an orphan
      // result — a Tool-role Message whose `origin` names a call with
      // no frame in this request (the ToolInvoke was shed, or the
      // result is being replayed against a trimmed history).
      val orphanCallId = Id[Event]("orphan-call-id")
      val events = List[Event](
        userText("hi"),
        toolResultMessage(orphanCallId, """{"hits":[]}"""),
        agentText("ok")
      )
      val frames = FrameBuilder.build(events)
      // FrameBuilder degrades the orphan to an agents-only Text frame.
      val fallback = frames.collect { case t: ContextFrame.Text => t }
        .filter(_.content.contains("orphan tool result"))
      fallback should have size 1
      fallback.head.content should include(orphanCallId.value)
      val rendered = Probe.renderFor(frames, TestAgent)
      // Nothing pairs on the wire, so no ToolResult message is emitted —
      // the shape that used to 400 the request never reaches the API.
      rendered.collect { case t: ProviderMessage.ToolResult => t } shouldBe empty
      rendered.collect { case u: ProviderMessage.User => u } should have size 1
    }

    "NOT emit an orphan fallback for a settled synthetic diagnostic's paired result" in {
      // The framework's own diagnostics settle their invoke before the
      // paired Tool-role Message lands. That message must fold (or
      // no-op) — a fallback frame here duplicates the directive prose
      // into the agent's context on every subsequent turn.
      val events = sigil.orchestrator.SyntheticDiagnostic(
        sigil.orchestrator.Directive.RefusalChallenge,
        TestAgent,
        convId,
        TestTopicId,
        disposition = sigil.event.MessageDisposition.Failure(recoverable = true)
      ).collect { case e: Event => e }
      val frames = FrameBuilder.build(events)
      frames.collect { case t: ContextFrame.Text => t.content }
        .filter(_.contains("orphan tool result")) shouldBe empty
      // One internal ToolCall frame, rendered as the out-of-band System
      // note the framework uses for its own directives.
      frames.collect { case tc: ContextFrame.ToolCall => tc } should have size 1
      val systems = Probe.renderFor(frames, TestAgent).collect { case s: ProviderMessage.System => s }
      systems should have size 1
      systems.head.content should include("find_capability")
    }

    "pair correctly when the ToolCall IS in the request" in {
      val callId = Id[Event]("normal-call-id")
      val frames = Vector[ContextFrame](
        ContextFrame.Text(content = "hi", participantId = TestUser, sourceEventId = Id[Event]("user")),
        ContextFrame.ToolCall(
          toolName = ToolName("vector_lookup"),
          argsJson = "{\"q\":\"x\"}",
          callId = callId,
          participantId = TestAgent,
          sourceEventId = Id[Event]("tc-event"),
          wireCallId = Some("call_wire_abc"), // upstream wire id
          state = ToolCallState.Complete("{\"hits\":[]}")
        )
      )
      val rendered = Probe.renderFor(frames, TestAgent)
      val tr = rendered.collect { case t: ProviderMessage.ToolResult => t }
      tr should have size 1
      // The wire id from the ToolCall must propagate to the result's pairing field.
      tr.head.toolCallId shouldBe "call_wire_abc"
    }

    "drop the orphan even when the request also has a valid pair (mixed scenario)" in {
      val orphanId = Id[Event]("orphan")
      val invoke = ToolInvoke(
        toolName = ToolName("vector_lookup"),
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicId,
        state = EventState.Complete
      )
      val events = List[Event](
        userText("hi"),
        toolResultMessage(orphanId, "orphan content"),
        invoke,
        toolResultMessage(invoke._id, "paired content")
      )
      val frames = FrameBuilder.build(events)
      val rendered = Probe.renderFor(frames, TestAgent)
      val tr = rendered.collect { case t: ProviderMessage.ToolResult => t }
      // Only the genuinely-paired call produces a wire result.
      tr should have size 1
      tr.head.content shouldBe "paired content"
    }
  }

  private def userText(text: String): Message = Message(
    participantId = TestUser,
    conversationId = convId,
    topicId = TestTopicId,
    content = Vector(ResponseContent.Text(text)),
    state = EventState.Complete
  )

  private def agentText(text: String): Message = Message(
    participantId = TestAgent,
    conversationId = convId,
    topicId = TestTopicId,
    content = Vector(ResponseContent.Text(text)),
    state = EventState.Complete
  )

  private def toolResultMessage(origin: Id[Event], text: String): Message = Message(
    participantId = TestAgent,
    conversationId = convId,
    topicId = TestTopicId,
    role = MessageRole.Tool,
    content = Vector(ResponseContent.Text(text)),
    state = EventState.Complete,
    origin = Some(origin)
  )
}
