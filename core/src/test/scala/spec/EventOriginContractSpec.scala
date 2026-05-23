package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ConversationView, ContextFrame, Conversation, FrameBuilder, ToolCallState}
import sigil.event.{Event, Message, MessageRole, ToolInvoke, ToolResults}
import sigil.signal.EventState
import sigil.tool.{ToolName, ToolSchema}
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

  private def completeInvoke(name: String): ToolInvoke =
    ToolInvoke(
      toolName = ToolName(name),
      participantId = TestAgent,
      conversationId = conversationId,
      topicId = TestTopicId,
      state = EventState.Complete
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
    "throw when a Tool-role event reaches FrameBuilder with no origin" in {
      // Bug #69 — the framework's "Tool-role events MUST have origin"
      // contract is enforced by FrameBuilder, the single rendering
      // boundary every Tool event must cross. A missing origin is
      // a programmer error and surfaces as a clear exception, not
      // as a degraded "additional tool output" frame.
      val invoke = completeInvoke("guarded")
      val orphan = toolMessage("oops, no origin", origin = None)
      val ex = intercept[IllegalStateException] {
        FrameBuilder.appendFor(FrameBuilder.appendFor(Vector.empty, invoke), orphan)
      }
      ex.getMessage should include ("origin")
      ex.getMessage should include ("Tool-role")
    }

    "fold into the parent ToolCall's state when origin is set on a Message" in {
      // Sigil #261 — Tool-role events no longer produce their own
      // frame; FrameBuilder.appendFor updates the matching ToolCall
      // to state = Complete(content, images).
      val invoke = completeInvoke("paired")
      val reply = toolMessage("the result", origin = Some(invoke._id))
      val frames = FrameBuilder.appendFor(FrameBuilder.appendFor(Vector.empty, invoke), reply)
      frames should have size 1
      val tc = frames.head.asInstanceOf[ContextFrame.ToolCall]
      tc.callId shouldBe invoke._id
      tc.state shouldBe ToolCallState.Complete("the result")
    }

    "fold correctly when origin is set on a typed Event subclass" in {
      // Same invariant for ToolResults / CapabilityResults / any
      // other Tool-role Event subclass — the typed payload renders
      // via stripEventBoilerplate, but the pairing path is identical.
      val invoke = completeInvoke("typed_paired")
      val results = ToolResults(
        schemas = List.empty[ToolSchema],
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete,
        origin = Some(invoke._id)
      )
      val frames = FrameBuilder.appendFor(FrameBuilder.appendFor(Vector.empty, invoke), results)
      frames should have size 1
      val tc = frames.head.asInstanceOf[ContextFrame.ToolCall]
      tc.callId shouldBe invoke._id
      tc.state shouldBe a[ToolCallState.Complete]
    }
  }

  // ---- multi-event tool emit: all share one origin ----

  "Multiple Tool-role events sharing one origin (bug #69 structural fix)" should {
    "fold the first matching result into ToolCall.state; later same-origin events surface as orphans" in {
      // TODO(#261): semantics changed, review — under the unified
      // ToolCall(state) model, only the first Tool-role event with a
      // matching origin folds the parent ToolCall into Complete. The
      // matching ToolCall is no longer Active after the first fold, so
      // subsequent same-origin events go through the orphan fallback
      // and become synthetic Text frames. The wire-level merging that
      // used to happen across N ToolResult frames is now a non-issue
      // because there is at most one result per call.
      val invoke = completeInvoke("multi_emit")
      val ack          = toolMessage("step 1: ack",          origin = Some(invoke._id))
      val suggestion   = toolMessage("step 2: schema",       origin = Some(invoke._id))
      val followup     = toolMessage("step 3: invocation",   origin = Some(invoke._id))
      val frames = List(ack, suggestion, followup).foldLeft(
        FrameBuilder.appendFor(Vector.empty, invoke)
      )(FrameBuilder.appendFor)

      // 1 Complete ToolCall + 2 orphan Text fallbacks.
      val toolCalls = frames.collect { case tc: ContextFrame.ToolCall => tc }
      toolCalls should have size 1
      toolCalls.head.callId shouldBe invoke._id
      toolCalls.head.state shouldBe ToolCallState.Complete("step 1: ack")
      val textOrphans = frames.collect { case t: ContextFrame.Text => t }
      textOrphans should have size 2
      textOrphans.foreach(_.content should include (invoke._id.value))
    }

    "interleaved frames with non-matching callId stay separate" in {
      // Sanity: if a tool emits Tool events for one call, then
      // another tool runs and emits its own Tool events, the two
      // groups don't blur. Each pairs to its own ToolInvoke and the
      // parent ToolCall frame transitions to Complete with that
      // event's content.
      val invokeA = completeInvoke("tool_a")
      val invokeB = completeInvoke("tool_b")
      val resultA = toolMessage("A's result", origin = Some(invokeA._id))
      val resultB = toolMessage("B's result", origin = Some(invokeB._id))
      val frames = List(invokeA, resultA, invokeB, resultB).foldLeft(Vector.empty[ContextFrame])(FrameBuilder.appendFor)
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
      val invoke = completeInvoke("ancient_call")
      // Stuff 20 unrelated frames between the invoke and its result.
      val filler: Vector[ContextFrame] = (1 to 20).toVector.map { i =>
        ContextFrame.Text(
          content = s"chatter $i",
          participantId = TestUser,
          sourceEventId = Id[Event](s"filler-$i")
        )
      }
      val starter = FrameBuilder.appendFor(Vector.empty, invoke) ++ filler
      val lateResult = toolMessage("answer to ancient call", origin = Some(invoke._id))
      val frames = FrameBuilder.appendFor(starter, lateResult)
      val tc = frames.collectFirst { case t: ContextFrame.ToolCall if t.callId == invoke._id => t }.get
      tc.state shouldBe ToolCallState.Complete("answer to ancient call")
    }

    "pair correctly even when an intervening different ToolInvoke is unresolved" in {
      // Pre-fix the scanner found the most-recent unresolved call
      // and would have paired with the wrong one. With origin, the
      // explicit pointer wins regardless of what's been resolved.
      val invokeA = completeInvoke("first_call")
      val invokeB = completeInvoke("second_unresolved")
      // resultA folds into invokeA, NOT into invokeB even though
      // invokeB is more recent.
      val resultA = toolMessage("first call's result", origin = Some(invokeA._id))
      val frames = List(invokeA, invokeB, resultA).foldLeft(Vector.empty[ContextFrame])(FrameBuilder.appendFor)
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
      val invoke = completeInvoke("traced_call")
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
      val invoke = completeInvoke("traced_chain").copy(origin = Some(userMsg._id))
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
      val ti = completeInvoke("noop")
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

  // ---- wire-level merge: multiple ToolResults → one function_call_output ----

  "Provider.renderFrames renders ToolCall(Complete) as one function_call_output (bug #69)" should {
    "produce a single ProviderMessage.ToolResult carrying the Complete state's content" in {
      // TODO(#261): semantics changed, review — under the unified
      // ToolCall(state) model the framework no longer needs to merge
      // multiple ToolResult frames; only the first Tool-role event
      // folds into the parent ToolCall's state and the rest become
      // orphan Text fallbacks. The wire still stays 1:1 by
      // construction (one ToolCall ⇒ one function_call_output), so
      // the rest of this spec exercises the steady-state shape only.
      import sigil.conversation.{ConversationView, TurnInput}
      import sigil.db.Model
      import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ProviderRequest}
      import sigil.provider.openai.OpenAIProvider
      import sigil.tool.core.CoreTools

      val invokeId: Id[Event] = Id("merge-test-invoke")
      val toolName = ToolName("multi_emit_test_tool")
      val frames: Vector[ContextFrame] = Vector(
        // A single ToolCall in Complete state — the only frame the
        // framework produces for a settled tool call under the new
        // unified model.
        ContextFrame.ToolCall(
          toolName = toolName,
          argsJson = "{}",
          callId = invokeId,
          participantId = TestAgent,
          sourceEventId = invokeId,
          state = ToolCallState.Complete("PRIMARY_RESULT_MARKER")
        )
      )
      val view = ConversationView(
        conversationId = conversationId,
        frames = frames
      )
      val req: ProviderRequest = ConversationRequest(
        conversationId = conversationId,
        modelId = Model.id("openai", "gpt-4o-mini"),
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
      val outputCount = "\"function_call_output\"".r.findAllIn(body).size
      outputCount shouldBe 1
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
