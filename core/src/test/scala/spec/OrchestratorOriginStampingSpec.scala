package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal}
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tool.model.{NoResponseInput, ResponseContent}
import spice.http.HttpRequest
import fabric.rw.*

/**
 * Orchestrator-side coverage of the `Event.origin` invariant
 * introduced in bug #69. The orchestrator's atomic-tool path
 * (`executeAtomic`) wraps each tool's emitted Stream[Event] and
 * stamps `origin = invokeId` on every event whose `origin` is None
 * — guaranteeing that Tool-role events from a tool's `executeTyped`
 * always pair with their parent ToolInvoke at the FrameBuilder
 * boundary.
 *
 * Verifies:
 *   - Tool that emits N events without origin → all N stamped with the
 *     ToolInvoke's id.
 *   - Tool that explicitly sets origin → orchestrator respects it
 *     (the override path for cross-chain attribution).
 */
class OrchestratorOriginStampingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Tool whose `execute` emits THREE Tool-role Messages with no
    * origin set — the orchestrator MUST stamp them. */
  private object MultiEmitTool extends Tool {
    override val name: ToolName = ToolName("multi_emit_origin_test")
    override def description: String = "Emits 3 Tool-role Messages, none with origin pre-set."
    override def inputRW: RW[? <: ToolInput] = summon[RW[NoResponseInput]]
    override def space: SpaceId = GlobalSpace
    override def execute(input: ToolInput, context: TurnContext): Stream[Event] = {
      def msg(text: String): Event = Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(text)),
        state = EventState.Complete,
        role = MessageRole.Tool
        // origin defaults to None — the orchestrator stamps it.
      )
      Stream.emits(List(msg("first"), msg("second"), msg("third")))
    }
    override def _id: Id[Tool] = Id[Tool](name.value)
  }

  /** A different parent — used to test the orchestrator's
    * "respect explicit origin" override path. */
  private val explicitOrigin: Id[Event] = Id("explicit-parent-pointer")

  /** Tool whose `execute` emits a Tool-role Message with origin
    * already set (to a different parent than the calling
    * ToolInvoke). The orchestrator MUST NOT overwrite it. */
  private object ExplicitOriginTool extends Tool {
    override val name: ToolName = ToolName("explicit_origin_test")
    override def description: String = "Emits a Tool-role Message with origin pre-set."
    override def inputRW: RW[? <: ToolInput] = summon[RW[NoResponseInput]]
    override def space: SpaceId = GlobalSpace
    override def execute(input: ToolInput, context: TurnContext): Stream[Event] =
      Stream.emit(Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text("explicit")),
        state = EventState.Complete,
        role = MessageRole.Tool,
        origin = Some(explicitOrigin)
      ))
    override def _id: Id[Tool] = Id[Tool](name.value)
  }

  private class StubProvider(toolName: String, callIdValue: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId(callIdValue)
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, toolName),
        ProviderEvent.ToolCallComplete(cid, NoResponseInput()),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def runWith(provider: Provider, tools: Vector[Tool], suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"origin-stamp-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = Model.id("test", "model"),
      instructions       = Instructions(),
      turnInput          = TurnInput(ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = tools
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Orchestrator.executeAtomic stamping (bug #69)" should {
    "stamp origin = invokeId on every Tool-role event a tool emitted without one" in {
      runWith(
        provider = new StubProvider(MultiEmitTool.name.value, "multi-emit-call"),
        tools    = Vector(MultiEmitTool),
        suffix   = "multi-emit"
      ).map { signals =>
        val invokes = signals.collect { case ti: ToolInvoke => ti }
        invokes should have size 1
        val invokeId = invokes.head._id

        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 3
        toolMessages.foreach { m =>
          withClue(s"unstamped tool message: $m: ") {
            m.origin shouldBe Some(invokeId)
          }
        }
        toolMessages.flatMap(_.content.collect { case ResponseContent.Text(t) => t }) shouldBe List(
          "first", "second", "third"
        )
        succeed
      }
    }

    "preserve a tool's explicit origin (orchestrator only stamps when origin is None)" in {
      runWith(
        provider = new StubProvider(ExplicitOriginTool.name.value, "explicit-origin-call"),
        tools    = Vector(ExplicitOriginTool),
        suffix   = "explicit-origin"
      ).map { signals =>
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        // The orchestrator did NOT overwrite the explicit parent —
        // tool authors who attach to a different parent retain that
        // attribution.
        toolMessages.head.origin shouldBe Some(explicitOrigin)
        succeed
      }
    }
  }
}
