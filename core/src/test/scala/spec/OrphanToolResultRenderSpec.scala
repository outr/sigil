package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, ToolCallState}
import sigil.event.Event
import sigil.provider.{Provider, ProviderMessage}
import sigil.tool.ToolName

/**
 * Regression for sigil bug #174 — the wire-side `renderFrames` guard
 * against orphan `ContextFrame.ToolResult` entries whose matching
 * `ContextFrame.ToolCall` isn't in the current request's frames.
 *
 * Without the guard, the renderer emitted a `ProviderMessage.ToolResult`
 * with the framework call_id (no `wireCallIdByEvent` entry to map
 * through), and OpenAI / DeepInfra / etc. 400'd the request with "No
 * tool call found for function call output with call_id ...". The
 * widge-server consumer hit this on every second user turn.
 *
 * Fix (defensive): when `wireCallIdByEvent` lacks an entry for the
 * orphan's callId, drop the frame and log a warning. Keeps the wire
 * request well-formed regardless of why the ToolCall was dropped
 * upstream.
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

  "Bug #174 — orphan ToolResult guard" should {

    "drop a ToolResult whose matching ToolCall isn't in the request" in {
      // TODO(#261): semantics changed, review — orphan ToolResult can
      // no longer reach the renderer; FrameBuilder collapses it into a
      // synthetic Text fallback before the renderer ever sees it. The
      // wire-side orphan guard is therefore unreachable under the
      // unified ToolCall(state) model. Keep the spec compiling by
      // exercising the equivalent shape: a Text fallback frame between
      // user and agent texts.
      val orphanCallId = Id[Event]("orphan-call-id")
      val frames = Vector[ContextFrame](
        ContextFrame.Text(content = "hi", participantId = TestUser, sourceEventId = Id[Event]("user")),
        ContextFrame.Text(
          content = s"[framework: orphan tool result for callId=${orphanCallId.value} — content: {\"hits\":[]}]",
          participantId = TestAgent,
          sourceEventId = Id[Event]("tr-event")
        ),
        ContextFrame.Text(content = "ok", participantId = TestAgent, sourceEventId = Id[Event]("agent"))
      )
      val rendered = Probe.renderFor(frames, TestAgent)
      // No ToolResult was ever in the vector — none on the wire either.
      rendered.collect { case t: ProviderMessage.ToolResult => t } shouldBe empty
      // Text frames render normally.
      rendered.collect { case u: ProviderMessage.User => u } should have size 1
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
      // TODO(#261): semantics changed, review — see the orphan-only
      // case above. Orphans never reach the renderer; we simulate the
      // FrameBuilder fallback (a Text frame) and keep the paired
      // ToolCall(state = Complete) frame as the only thing the
      // renderer is asked to produce a ToolResult message from.
      val orphanId = Id[Event]("orphan")
      val pairedId = Id[Event]("paired")
      val frames = Vector[ContextFrame](
        ContextFrame.Text(content = "hi", participantId = TestUser, sourceEventId = Id[Event]("user")),
        ContextFrame.Text(
          content = s"[framework: orphan tool result for callId=${orphanId.value} — content: orphan content]",
          participantId = TestAgent,
          sourceEventId = Id[Event]("tr-orphan")
        ),
        ContextFrame.ToolCall(
          toolName = ToolName("vector_lookup"),
          argsJson = "{}",
          callId = pairedId,
          participantId = TestAgent,
          sourceEventId = Id[Event]("tc-paired"),
          state = ToolCallState.Complete("paired content")
        )
      )
      val rendered = Probe.renderFor(frames, TestAgent)
      val tr = rendered.collect { case t: ProviderMessage.ToolResult => t }
      // Only the paired ToolResult survives.
      tr should have size 1
      tr.head.content shouldBe "paired content"
    }
  }
}
