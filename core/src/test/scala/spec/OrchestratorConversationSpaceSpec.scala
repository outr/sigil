package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.{SpaceId, TurnContext}
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for bug #46 — the orchestrator's atomic-tool dispatch
 * path used to synthesize a stand-in `Conversation` that dropped
 * every field not present on `ConversationRequest` (`space`,
 * `participants`, `clearedAt`, `created`/`modified`). Apps reading
 * `ctx.conversation.space` from inside a tool saw `GlobalSpace`
 * regardless of what the persisted conversation actually carried,
 * silently breaking multi-tenant scoping (per-tenant Qdrant
 * collections, per-tenant secrets, etc.).
 *
 * Fix: the orchestrator accepts the full Conversation as a
 * parameter from the caller (in production: `Sigil.runAgentTurn`,
 * which gets the live row reloaded each iteration via
 * `runAgentLoop`), and threads it through the atomic-tool
 * `TurnContext` unchanged.
 */
class OrchestratorConversationSpaceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Capture-tool — records the `ctx.conversation` it receives so
    * the test can assert against it. Single-shot per spec; the
    * captured reference is observed AFTER the orchestrator stream
    * drains. */
  private val captured: AtomicReference[Option[Conversation]] = new AtomicReference(None)

  case class CaptureInput() extends ToolInput derives RW

  case object CaptureTool extends TypedTool[CaptureInput](
    name = ToolName("capture"),
    description = "test-only — captures the TurnContext's conversation"
  ) {
    override protected def executeTyped(input: CaptureInput, ctx: TurnContext): Stream[Event] = {
      captured.set(Some(ctx.conversation))
      Stream.empty
    }
  }
  ToolInput.register(summon[RW[CaptureInput]])
  sigil.tool.Tool.register(fabric.rw.RW.static(CaptureTool))

  /** Provider that synthesizes a single atomic `capture` tool call.
    * Single content block, no streaming → atomic dispatch path. */
  private class CaptureCallProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("capture-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, "capture"),
        ProviderEvent.ToolCallComplete(cid, CaptureInput()),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  "Orchestrator atomic-tool dispatch" should {

    "thread the caller's Conversation (with custom SpaceId) into ctx.conversation (bug #46)" in {
      val convId = Conversation.id(s"orch-space-${rapid.Unique()}")
      val view   = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
      // Use TestSpace — already registered in TestSigil. Confirms a
      // non-GlobalSpace value round-trips into the tool.
      val conv   = Conversation(
        topics = TestTopicStack,
        space  = TestSpace,
        _id    = convId
      )
      val request = ConversationRequest(
        conversationId     = convId,
        modelId            = Model.id("test", "model"),
        instructions       = Instructions(),
        turnInput          = TurnInput(view),
        currentMode        = ConversationMode,
        currentTopic       = TestTopicEntry,
        previousTopics     = Nil,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        chain              = List(TestUser, TestAgent),
        tools              = Vector(CaptureTool)
      )
      captured.set(None)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- Orchestrator.process(TestSigil, new CaptureCallProvider, request, conv).toList
      } yield {
        val seen = captured.get().getOrElse(fail("CaptureTool didn't fire"))
        seen._id shouldBe convId
        seen.space shouldBe TestSpace
        seen.topics shouldBe TestTopicStack
      }
    }
  }
}
