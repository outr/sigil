package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, Conversation, FrameBuilder}
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
 *   - **Multi-event tool emissions** (multiple Tool-role events from
 *     one `executeTyped` all share the same origin and pair to the
 *     same call_id; `Provider.renderFrames` merges them into one
 *     wire-level result).
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

    "pair correctly when origin is set on a Message" in {
      val invoke = completeInvoke("paired")
      val reply = toolMessage("the result", origin = Some(invoke._id))
      val frames = FrameBuilder.appendFor(FrameBuilder.appendFor(Vector.empty, invoke), reply)
      frames should have size 2
      frames.last shouldBe a[ContextFrame.ToolResult]
      val tr = frames.last.asInstanceOf[ContextFrame.ToolResult]
      tr.callId shouldBe invoke._id
      tr.content shouldBe "the result"
    }

    "pair correctly when origin is set on a typed Event subclass" in {
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
      frames should have size 2
      frames.last.asInstanceOf[ContextFrame.ToolResult].callId shouldBe invoke._id
    }
  }

  // ---- multi-event tool emit: all share one origin ----

  "Multiple Tool-role events from one executeTyped (bug #69 structural fix)" should {
    "all pair to the same call_id when sharing the same origin" in {
      // Tool authors emit ack + suggestion + diagnostic, all
      // MessageRole.Tool, all stamped with the same origin by the
      // orchestrator. Pre-fix the second-and-onward events became
      // orphan-System frames; post-fix they all become ToolResult
      // frames sharing the call_id, ready for renderFrames to merge
      // into one wire-level function_call_output.
      val invoke = completeInvoke("multi_emit")
      val ack          = toolMessage("step 1: ack",          origin = Some(invoke._id))
      val suggestion   = toolMessage("step 2: schema",       origin = Some(invoke._id))
      val followup     = toolMessage("step 3: invocation",   origin = Some(invoke._id))
      val frames = List(ack, suggestion, followup).foldLeft(
        FrameBuilder.appendFor(Vector.empty, invoke)
      )(FrameBuilder.appendFor)

      // 1 ToolCall + 3 ToolResult frames, all sharing the same callId.
      frames should have size 4
      val toolResults = frames.collect { case tr: ContextFrame.ToolResult => tr }
      toolResults should have size 3
      toolResults.foreach { tr =>
        withClue(s"toolResult callId mismatch: $tr: ") {
          tr.callId shouldBe invoke._id
        }
      }
      // Order preserved.
      toolResults.map(_.content) shouldBe List("step 1: ack", "step 2: schema", "step 3: invocation")
    }

    "interleaved frames with non-matching callId stay separate" in {
      // Sanity: if a tool emits Tool events for one call, then
      // another tool runs and emits its own Tool events, the two
      // groups don't blur. Each pairs to its own ToolInvoke.
      val invokeA = completeInvoke("tool_a")
      val invokeB = completeInvoke("tool_b")
      val resultA = toolMessage("A's result", origin = Some(invokeA._id))
      val resultB = toolMessage("B's result", origin = Some(invokeB._id))
      val frames = List(invokeA, resultA, invokeB, resultB).foldLeft(Vector.empty[ContextFrame])(FrameBuilder.appendFor)
      val toolResults = frames.collect { case tr: ContextFrame.ToolResult => tr }
      toolResults.find(_.callId == invokeA._id).map(_.content) shouldBe Some("A's result")
      toolResults.find(_.callId == invokeB._id).map(_.content) shouldBe Some("B's result")
    }
  }

  // ---- cross-turn / position-independent ----

  "Position-independent pairing via origin" should {
    "pair an event whose origin points to a much earlier ToolInvoke" in {
      // Pre-fix `pairedCallId` scanned for the most-recent unresolved
      // ToolCall — temporal proximity was load-bearing. With explicit
      // origin, position no longer matters: an event with origin
      // pointing to a ToolCall buried 50 frames back still pairs.
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
      val tr = frames.last.asInstanceOf[ContextFrame.ToolResult]
      tr.callId shouldBe invoke._id
      tr.content shouldBe "answer to ancient call"
    }

    "pair correctly even when an intervening different ToolInvoke is unresolved" in {
      // Pre-fix the scanner found the most-recent unresolved call
      // and would have paired with the wrong one. With origin, the
      // explicit pointer wins regardless of what's been resolved.
      val invokeA = completeInvoke("first_call")
      val invokeB = completeInvoke("second_unresolved")
      // resultA pairs to invokeA, NOT to invokeB even though invokeB
      // is more recent.
      val resultA = toolMessage("first call's result", origin = Some(invokeA._id))
      val frames = List(invokeA, invokeB, resultA).foldLeft(Vector.empty[ContextFrame])(FrameBuilder.appendFor)
      val toolResults = frames.collect { case tr: ContextFrame.ToolResult => tr }
      toolResults should have size 1
      toolResults.head.callId shouldBe invokeA._id
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

  "Provider.renderFrames merges consecutive ToolResults sharing a callId (bug #69)" should {
    "produce a single ProviderMessage.ToolResult with content joined when multiple Tool events share an origin" in {
      // The frame model produces N ToolResult frames sharing one
      // callId; renderFrames collapses them so the wire stays 1:1
      // with what providers expect (`function_call` ↔
      // `function_call_output`). Tested via a real provider's
      // requestConverter — the merge is part of the shared
      // `Provider.translate` path so any provider exercises it.
      import sigil.conversation.{ConversationView, TurnInput}
      import sigil.db.Model
      import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ProviderRequest}
      import sigil.provider.openai.OpenAIProvider
      import sigil.tool.core.CoreTools

      val invokeId: Id[Event] = Id("merge-test-invoke")
      val toolName = ToolName("multi_emit_test_tool")
      val frames: Vector[ContextFrame] = Vector(
        // ToolCall frame — what FrameBuilder would produce from a
        // settled ToolInvoke for the agent's tool call.
        ContextFrame.ToolCall(
          toolName = toolName,
          argsJson = "{}",
          callId = invokeId,
          participantId = TestAgent,
          sourceEventId = invokeId
        ),
        // Three ToolResult frames sharing the same callId — the
        // exact shape the new framework produces when a tool emits
        // multiple Tool-role events stamped with the same origin.
        ContextFrame.ToolResult(
          callId = invokeId,
          content = "PRIMARY_RESULT_MARKER",
          sourceEventId = Id[Event]("res-1")
        ),
        ContextFrame.ToolResult(
          callId = invokeId,
          content = "FOLLOWUP_RESULT_MARKER",
          sourceEventId = Id[Event]("res-2")
        ),
        ContextFrame.ToolResult(
          callId = invokeId,
          content = "TRAILING_RESULT_MARKER",
          sourceEventId = Id[Event]("res-3")
        )
      )
      val view = ConversationView(
        conversationId = conversationId,
        frames = frames,
        _id = ConversationView.idFor(conversationId)
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

      // All three result markers appear — the content is preserved.
      body should include ("PRIMARY_RESULT_MARKER")
      body should include ("FOLLOWUP_RESULT_MARKER")
      body should include ("TRAILING_RESULT_MARKER")

      // BUT: only ONE `function_call_output` for the merge-test
      // invoke. The wire stays 1:1 — the three frames collapsed
      // into one.
      val outputCount = "\"function_call_output\"".r.findAllIn(body).size
      outputCount shouldBe 1

      // Order preserved: primary appears before followup which
      // appears before trailing.
      val primaryIdx = body.indexOf("PRIMARY_RESULT_MARKER")
      val followupIdx = body.indexOf("FOLLOWUP_RESULT_MARKER")
      val trailingIdx = body.indexOf("TRAILING_RESULT_MARKER")
      primaryIdx should be < followupIdx
      followupIdx should be < trailingIdx
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
