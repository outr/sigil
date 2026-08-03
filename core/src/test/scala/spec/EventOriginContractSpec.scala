package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ConversationView, ContextFrame, Conversation, FrameBuilder, ToolCallState}
import sigil.event.{Event, Message, MessageRole, MessageVisibility, ToolInvoke}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent

/**
 * Contract coverage for the framework-wide `Event.origin` parent-pointer
 * invariant introduced in bug #69. The invariant is load-bearing for:
 *
 *   - **Tool-result pairing** ([[FrameBuilder]] looks up `event.origin`
 *     instead of scanning for "most-recent unresolved" ToolCall).
 *   - **Multi-event tool emissions** (multiple Tool-role events that
 *     share the same origin pair to the same call_id;
 *     `Provider.renderFrames` merges them into one wire-level result).
 *   - **Cross-turn delivery** (origin is position-independent — an
 *     event with origin pointing to a long-finished call still pairs
 *     correctly).
 *   - **Replay** (persisted origin survives RW round-trip).
 *   - **UI lineage walks** (every event chains back to its
 *     conversational root; collapse-around-user-message is a walk).
 *
 * Every spec in here exercises one of these guarantees. Together
 * they ensure the contract doesn't silently drift — if a future
 * change breaks the parent-pointer mechanism, this spec catches it.
 */
class EventOriginContractSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val conversationId = Conversation.id("origin-contract-conv")

  // `appendFor` early-returns for non-Complete events, so an Active
  // ToolInvoke alone produces no frame. The pair-folding tests below
  // exercise [[FrameBuilder.appendFor]]'s "fold a Tool-role result
  // into the matching ToolCall(Active)" branch — they construct the
  // ToolCall(Active) frame directly via [[activeFrameFor]] rather
  // than relying on appendFor to materialise it.
  private def activeInvoke(name: String): ToolInvoke =
    ToolInvoke(
      toolName = ToolName.parse(name).fold(sys.error, identity),
      participantId = TestAgent,
      conversationId = conversationId,
      topicId = TestTopicId,
      state = EventState.Active
    )

  private def activeFrameFor(invoke: ToolInvoke): ContextFrame.ToolCall =
    ContextFrame.ToolCall(
      toolName = invoke.toolName,
      argsJson = "{}",
      callId = invoke._id,
      participantId = invoke.participantId,
      sourceEventId = invoke._id,
      state = ToolCallState.Active
    )

  private def toolMessage(text: String, origin: Option[Id[Event]]): Message =
    Message(
      participantId = TestAgent,
      conversationId = conversationId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      origin = origin
    )

  // ---- the invariant: Tool-role events MUST carry origin ----

  "Event.origin invariant — MessageRole.Tool" should {
    "surface a Tool-role event with no origin as an agents-only frame" in {
      // The framework's "Tool-role events MUST have origin" contract is
      // enforced at FrameBuilder, the single rendering boundary every
      // Tool event crosses. A missing origin is a programmer error, but
      // it degrades rather than throwing: one malformed event must not
      // be able to wedge a whole conversation. The event is surfaced as
      // a synthetic agents-only frame (and logged) so the data is
      // visible without reaching a user.
      val invoke = activeInvoke("guarded")
      val orphan = toolMessage("oops, no origin", origin = None)
      val frames = FrameBuilder.appendFor(FrameBuilder.appendFor(Vector.empty, invoke), orphan)
      val degraded = frames.collect { case t: ContextFrame.Text => t }
      degraded should have size 1
      degraded.head.content should include (orphan._id.value)
      degraded.head.visibility shouldBe MessageVisibility.Agents
    }

    "fold into the parent ToolCall's state when origin is set on a Message" in {
      // Sigil #261 — Tool-role events no longer produce their own
      // frame; FrameBuilder.appendFor updates the matching ToolCall
      // to state = Complete(content, images).
      val invoke = activeInvoke("paired")
      val reply = toolMessage("the result", origin = Some(invoke._id))
      val frames = FrameBuilder.appendFor(Vector[ContextFrame](activeFrameFor(invoke)), reply)
      frames should have size 1
      val tc = frames.head.asInstanceOf[ContextFrame.ToolCall]
      tc.callId shouldBe invoke._id
      tc.state shouldBe ToolCallState.Complete("the result")
    }

  }

  // ---- multi-event tool emit: all share one origin ----

  "Multiple Tool-role events sharing one origin (bug #69 structural fix)" should {
    "fold the first matching result into ToolCall.state; later same-origin events are no-ops" in {
      // Under the unified ToolCall(state) model there is at most one
      // result per call: the first Tool-role event settles the parent
      // frame, and later same-origin events find it settled and change
      // nothing. This is exactly what the live settle path does against
      // the persisted invoke — a divergence here would mean a rebuilt
      // conversation carried frames production never had.
      val invoke = activeInvoke("multi_emit")
      val ack          = toolMessage("step 1: ack",          origin = Some(invoke._id))
      val suggestion   = toolMessage("step 2: schema",       origin = Some(invoke._id))
      val followup     = toolMessage("step 3: invocation",   origin = Some(invoke._id))
      val frames = List(ack, suggestion, followup).foldLeft(
        Vector[ContextFrame](activeFrameFor(invoke))
      )(FrameBuilder.appendFor)

      val toolCalls = frames.collect { case tc: ContextFrame.ToolCall => tc }
      toolCalls should have size 1
      toolCalls.head.callId shouldBe invoke._id
      toolCalls.head.state shouldBe ToolCallState.Complete("step 1: ack")
      // No orphan fallbacks — the parent frame exists, so nothing is orphaned.
      frames.collect { case t: ContextFrame.Text => t } shouldBe empty
    }

    "interleaved frames with non-matching callId stay separate" in {
      // Sanity: if a tool emits Tool events for one call, then
      // another tool runs and emits its own Tool events, the two
      // groups don't blur. Each pairs to its own ToolInvoke and the
      // parent ToolCall frame transitions to Complete with that
      // event's content.
      val invokeA = activeInvoke("tool_a")
      val invokeB = activeInvoke("tool_b")
      val resultA = toolMessage("A's result", origin = Some(invokeA._id))
      val resultB = toolMessage("B's result", origin = Some(invokeB._id))
      val seed = Vector[ContextFrame](activeFrameFor(invokeA), activeFrameFor(invokeB))
      val frames = List(resultA, resultB).foldLeft(seed)(FrameBuilder.appendFor)
      val byCallId = frames.collect { case tc: ContextFrame.ToolCall => tc.callId -> tc }.toMap
      byCallId(invokeA._id).state shouldBe ToolCallState.Complete("A's result")
      byCallId(invokeB._id).state shouldBe ToolCallState.Complete("B's result")
    }
  }

  // ---- cross-turn / position-independent ----

  "Position-independent pairing via origin" should {
    "pair an event whose origin points to a much earlier ToolInvoke" in {
      // Pre-fix `pairedCallId` scanned for the most-recent unresolved
      // ToolCall — temporal proximity was load-bearing. With explicit
      // origin, position no longer matters: an event with origin
      // pointing to a ToolCall buried 20 frames back still folds.
      val invoke = activeInvoke("ancient_call")
      // Stuff 20 unrelated frames between the invoke and its result.
      val filler: Vector[ContextFrame] = (1 to 20).toVector.map { i =>
        ContextFrame.Text(
          content = s"chatter $i",
          participantId = TestUser,
          sourceEventId = Id[Event](s"filler-$i")
        )
      }
      val starter = Vector[ContextFrame](activeFrameFor(invoke)) ++ filler
      val lateResult = toolMessage("answer to ancient call", origin = Some(invoke._id))
      val frames = FrameBuilder.appendFor(starter, lateResult)
      val tc = frames.collectFirst { case t: ContextFrame.ToolCall if t.callId == invoke._id => t }.get
      tc.state shouldBe ToolCallState.Complete("answer to ancient call")
    }

    "pair correctly even when an intervening different ToolInvoke is unresolved" in {
      // Pre-fix the scanner found the most-recent unresolved call
      // and would have paired with the wrong one. With origin, the
      // explicit pointer wins regardless of what's been resolved.
      val invokeA = activeInvoke("first_call")
      val invokeB = activeInvoke("second_unresolved")
      // resultA folds into invokeA, NOT into invokeB even though
      // invokeB is more recent.
      val resultA = toolMessage("first call's result", origin = Some(invokeA._id))
      val seed = Vector[ContextFrame](activeFrameFor(invokeA), activeFrameFor(invokeB))
      val frames = FrameBuilder.appendFor(seed, resultA)
      val byCallId = frames.collect { case tc: ContextFrame.ToolCall => tc.callId -> tc }.toMap
      byCallId(invokeA._id).state shouldBe ToolCallState.Complete("first call's result")
      byCallId(invokeB._id).state shouldBe ToolCallState.Active
    }
  }

  // ---- lineage walks (UI feature) ----

  "Origin chain — lineage walks for UI features" should {
    "expose the parent ToolInvoke's id from a Tool-result Message" in {
      // The collapse-around-user-message UI feature walks `origin`
      // hops from a leaf event back to its conversational root. A
      // single hop from a tool's emitted Message reaches the
      // ToolInvoke that called it.
      val invoke = activeInvoke("traced_call")
      val reply = toolMessage("traced reply", origin = Some(invoke._id))
      reply.origin shouldBe Some(invoke._id)
    }

    "form a multi-hop chain user → toolInvoke → toolResult" in {
      // User types a message → agent emits ToolInvoke (origin = user
      // message) → tool emits ToolResult (origin = ToolInvoke). Walk
      // up: ToolResult.origin → ToolInvoke. ToolInvoke.origin → user
      // Message. user Message.origin → None (root).
      val userMsg = Message(
        participantId = TestUser,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("please use the tool")),
        state = EventState.Complete
        // origin = None — user's first message is a conversational root.
      )
      val invoke = activeInvoke("traced_chain").copy(origin = Some(userMsg._id))
      val reply  = toolMessage("done", origin = Some(invoke._id))

      val byId: Map[Id[Event], Event] = Map(userMsg._id -> userMsg, invoke._id -> invoke, reply._id -> reply)
      def ancestors(start: Event): List[Event] = start.origin match {
        case Some(parentId) => byId.get(parentId).toList.flatMap(p => p :: ancestors(p))
        case None           => Nil
      }
      val chain = ancestors(reply)
      chain.map(_._id) shouldBe List(invoke._id, userMsg._id)
      chain.last.origin shouldBe None
    }
  }

  // ---- withOrigin contract: every Event subclass implements it ----

  "withOrigin (every concrete Event implements it via copy)" should {
    "round-trip on Message" in {
      val msg = Message(
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete
      )
      val parent: Id[Event] = Id("synthetic-parent")
      msg.withOrigin(Some(parent)).origin shouldBe Some(parent)
      msg.withOrigin(None).origin shouldBe None
    }

    "round-trip on ToolInvoke" in {
      val ti = activeInvoke("noop")
      val parent: Id[Event] = Id("synthetic-parent")
      ti.withOrigin(Some(parent)).origin shouldBe Some(parent)
    }

    "preserve all other fields when stamping origin" in {
      // The orchestrator's stamp pass uses withOrigin; if it dropped
      // any other field the stamped event would lose its identity.
      val original = Message(
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("primary")),
        state = EventState.Complete,
        role = MessageRole.Tool
      )
      val stamped = original.withOrigin(Some(Id("p"))).asInstanceOf[Message]
      stamped._id shouldBe original._id
      stamped.participantId shouldBe original.participantId
      stamped.content shouldBe original.content
      stamped.role shouldBe original.role
      stamped.state shouldBe original.state
      stamped.origin shouldBe Some(Id[Event]("p"))
    }
  }

  // ---- RW round-trip: origin survives persistence ----

  "Event.origin RW round-trip" should {
    "round-trip a Message's origin through fabric serialization" in {
      import fabric.rw.RW
      val parent: Id[Event] = Id("persisted-parent")
      val msg: Event = Message(
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete,
        role = MessageRole.Tool,
        origin = Some(parent)
      )
      val rw = summon[RW[Event]]
      val json = rw.read(msg)
      val back = rw.write(json)
      back shouldBe a[Message]
      back.origin shouldBe Some(parent)
    }

    "round-trip None origin (the legacy / root case)" in {
      import fabric.rw.RW
      val msg: Event = Message(
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete
        // origin = None default
      )
      val rw = summon[RW[Event]]
      val back = rw.write(rw.read(msg))
      back.origin shouldBe None
    }
  }

  // ---- wire-level: settled ToolInvoke → one function_call_output ----

  "Provider.renderFrames renders ToolCall(Complete) as one function_call_output (bug #69)" should {
    "produce a single ProviderMessage.ToolResult carrying the Complete state's content" in {
      // Driven from events through the real projection: a settled
      // invoke plus THREE Tool-role events sharing its origin. The
      // projection collapses them into one ToolCall frame, so the wire
      // carries exactly one `function_call_output` — the shape
      // Anthropic and OpenAI both require.
      import sigil.conversation.{ConversationView, TurnInput}
      import sigil.db.Model
      import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ProviderRequest}
      import sigil.provider.openai.OpenAIProvider
      import sigil.tool.core.CoreTools

      val toolName = ToolName("multi_emit_test_tool")
      val invoke = ToolInvoke(
        toolName = toolName,
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete
      )
      val frames: Vector[ContextFrame] = FrameBuilder.build(List[Event](
        invoke,
        toolMessage("PRIMARY_RESULT_MARKER", origin = Some(invoke._id)),
        toolMessage("SECONDARY_MARKER", origin = Some(invoke._id)),
        toolMessage("TERTIARY_MARKER", origin = Some(invoke._id))
      ))
      frames.collect { case tc: ContextFrame.ToolCall => tc } should have size 1
      val view = ConversationView(
        conversationId = conversationId,
        frames = frames
      )
      val req: ProviderRequest = ConversationRequest(
        conversationId = conversationId,
        model = TestSigil.testModel(Model.id("openai", "gpt-4o-mini")),
        instructions = Instructions(),
        turnInput = TurnInput(view),
        currentMode = ConversationMode,
        currentTopic = TestTopicEntry,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
        tools = CoreTools.all,
        chain = List(TestUser, TestAgent)
      )
      val provider = OpenAIProvider(apiKey = "sk-test", sigilRef = TestSigil)
      val body = provider.requestConverter(req).sync().content match {
        case Some(c: spice.http.content.StringContent) => c.value
        case _ => ""
      }

      body should include ("PRIMARY_RESULT_MARKER")
      body should not include "SECONDARY_MARKER"
      val outputCount = "\"function_call_output\"".r.findAllIn(body).size
      outputCount shouldBe 1
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
