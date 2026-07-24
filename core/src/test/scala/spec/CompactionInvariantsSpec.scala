package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, Topic}
import sigil.conversation.compression.{CompactionInvariant, StandardIntraTurnCompactor, TurnEventsContext}
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent

/**
 * Exercises the typed [[CompactionInvariant]] pipeline: each standard
 * invariant in isolation, then composition through
 * [[StandardIntraTurnCompactor.selectFoldable]].
 */
class CompactionInvariantsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId: Id[Conversation] = Conversation.id(s"invariant-spec-${rapid.Unique()}")

  private def userMsg(text: String, ts: Long = 1L): Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicId,
      role = MessageRole.Standard,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      timestamp = Timestamp(ts)
    )

  private def agentMsg(text: String, ts: Long = 1L): Message =
    Message(
      participantId = TestAgent,
      conversationId = convId,
      topicId = TestTopicId,
      role = MessageRole.Standard,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      timestamp = Timestamp(ts)
    )

  private def toolInvoke(name: String, ts: Long = 1L, originId: Option[Id[Event]] = None): ToolInvoke =
    ToolInvoke(
      toolName = ToolName(name),
      participantId = TestAgent,
      conversationId = convId,
      topicId = TestTopicId,
      state = EventState.Complete,
      timestamp = Timestamp(ts),
      origin = originId
    )

  /**
   * A unified settled transaction (#265) — the result folded onto the
   * invoke itself; no separate Tool-role event exists.
   */
  private def settledInvoke(name: String, ts: Long = 1L, resultText: String = "ok"): ToolInvoke =
    toolInvoke(name, ts).copy(
      output = sigil.tool.TextToolOutput(resultText),
      outcome = sigil.event.ToolOutcome.Success
    )

  private def toolResult(text: String, originId: Id[Event], ts: Long = 1L): Message =
    Message(
      participantId = TestAgent,
      conversationId = convId,
      topicId = TestTopicId,
      role = MessageRole.Tool,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      timestamp = Timestamp(ts),
      origin = Some(originId)
    )

  private val emptyCtx: TurnEventsContext =
    TurnEventsContext(conversationId = convId, claimedAt = None, agentId = None)

  "CompactionInvariant.CurrentUserTaskMessage" should {

    "protect the most-recent user task when ctx.claimedAt is None (#316 safe-by-default)" in Task {
      val older = userMsg("old task", ts = 10L)
      val recent = userMsg("please find the bug", ts = 100L)
      val agent = agentMsg("working on it", ts = 110L)
      val ids = CompactionInvariant.CurrentUserTaskMessage.applicableIds(Vector(older, recent, agent), emptyCtx)
      ids shouldBe Set(recent._id)
    }

    "protect nothing when there are no user tasks and no claim" in Task {
      val a = agentMsg("autonomous output", ts = 10L)
      CompactionInvariant.CurrentUserTaskMessage.applicableIds(Vector(a), emptyCtx) shouldBe empty
    }

    "protect a user-authored Standard-role Message inside the claim window" in Task {
      val u = userMsg("please find the bug", ts = 100L)
      val ctx = emptyCtx.copy(claimedAt = Some(Timestamp(50L)))
      val ids = CompactionInvariant.CurrentUserTaskMessage.applicableIds(Vector(u), ctx)
      ids shouldBe Set(u._id)
    }

    "ignore user-authored Messages from before the claim" in Task {
      val u = userMsg("old task", ts = 10L)
      val ctx = emptyCtx.copy(claimedAt = Some(Timestamp(100L)))
      val ids = CompactionInvariant.CurrentUserTaskMessage.applicableIds(Vector(u), ctx)
      ids shouldBe empty
    }

    "ignore agent-authored Standard-role Message ids" in Task {
      val a = agentMsg("here is the bug", ts = 100L)
      val ctx = emptyCtx.copy(claimedAt = Some(Timestamp(50L)))
      val ids = CompactionInvariant.CurrentUserTaskMessage.applicableIds(Vector(a), ctx)
      ids shouldBe empty
    }

    "ignore Tool-role events" in Task {
      val ti = toolInvoke("read_file", ts = 100L)
      val ctx = emptyCtx.copy(claimedAt = Some(Timestamp(50L)))
      val ids = CompactionInvariant.CurrentUserTaskMessage.applicableIds(Vector(ti), ctx)
      ids shouldBe empty
    }
  }

  "CompactionInvariant.CurrentAgentClaimAnchor" should {

    "be a no-op when ctx.claimedAt is None" in Task {
      val events = Vector[Event](userMsg("a", ts = 10L), toolInvoke("read", ts = 20L))
      CompactionInvariant.CurrentAgentClaimAnchor.applicableIds(events, emptyCtx) shouldBe empty
    }

    "protect the first event at or after the claim timestamp" in Task {
      val preClaim = userMsg("earlier", ts = 5L)
      val anchor = userMsg("the new task", ts = 100L)
      val later = toolInvoke("read_one", ts = 110L)
      val events: Vector[Event] = Vector(preClaim, anchor, later)
      val ctx = emptyCtx.copy(claimedAt = Some(Timestamp(100L)))
      val ids = CompactionInvariant.CurrentAgentClaimAnchor.applicableIds(events, ctx)
      ids shouldBe Set(anchor._id)
    }

    "be a no-op when no event has timestamp >= claimedAt" in Task {
      val events: Vector[Event] = Vector(userMsg("old", ts = 5L))
      val ctx = emptyCtx.copy(claimedAt = Some(Timestamp(100L)))
      CompactionInvariant.CurrentAgentClaimAnchor.applicableIds(events, ctx) shouldBe empty
    }
  }

  "CompactionInvariant.PairedToolResult" should {

    "protect both the invoke and the paired tool-role result" in Task {
      val invoke = toolInvoke("read_file", ts = 10L)
      val result = toolResult("file contents", originId = invoke._id, ts = 11L)
      val events: Vector[Event] = Vector(invoke, result)
      val ids = CompactionInvariant.PairedToolResult().applicableIds(events, emptyCtx)
      ids shouldBe Set(invoke._id, result._id)
    }

    "with unsettledOnly=true, only protect invokes whose paired result is absent" in Task {
      val unpaired = toolInvoke("running_now", ts = 10L)
      val paired = toolInvoke("done_already", ts = 20L)
      val result = toolResult("output", originId = paired._id, ts = 21L)
      val events: Vector[Event] = Vector(unpaired, paired, result)
      val ids = CompactionInvariant.PairedToolResult(unsettledOnly = true)
        .applicableIds(events, emptyCtx)
      ids shouldBe Set(unpaired._id)
    }

    "with unsettledOnly=true, NOT protect a unified settled invoke — its result IS on the event" in Task {
      // The #265 shape: no Tool-role result event exists, the settling
      // delta folded output/outcome onto the invoke. Pre-fix this was
      // misclassified as "result absent" (and with the default flag,
      // never protected as a pair at all).
      val unified = settledInvoke("grep", ts = 10L, resultText = "3 matches")
      val inflight = toolInvoke("slow_sweep", ts = 20L) // Complete but outcome still Pending
      val events: Vector[Event] = Vector(unified, inflight)
      val ids = CompactionInvariant.PairedToolResult(unsettledOnly = true)
        .applicableIds(events, emptyCtx)
      ids shouldBe Set(inflight._id)
    }
  }

  "CompactionInvariant.UndeliveredToolResults" should {

    "protect an active-turn settled invoke the agent has not yet seen" in Task {
      val ut = userMsg("run the sweep", ts = 100L)
      val sweep = settledInvoke("refactor_sweep", ts = 110L, resultText = "converged; compile next")
      val ctx = emptyCtx.copy(claimedAt = Some(Timestamp(100L)))
      val ids = CompactionInvariant.UndeliveredToolResults.applicableIds(Vector(ut, sweep), ctx)
      ids shouldBe Set(sweep._id)
    }

    "release the invoke once its result has been delivered into a consumed prompt" in Task {
      val ut = userMsg("run the sweep", ts = 100L)
      val sweep = settledInvoke("refactor_sweep", ts = 110L)
      val ctx = emptyCtx.copy(
        claimedAt = Some(Timestamp(100L)),
        deliveredToolResults = Set(sweep._id)
      )
      CompactionInvariant.UndeliveredToolResults.applicableIds(Vector(ut, sweep), ctx) shouldBe empty
    }

    "protect an in-flight invoke — folding it would hide its EVENTUAL result the same way" in Task {
      val ut = userMsg("run the sweep", ts = 100L)
      val running = toolInvoke("refactor_sweep", ts = 110L) // outcome still Pending
      val ctx = emptyCtx.copy(claimedAt = Some(Timestamp(100L)))
      val ids = CompactionInvariant.UndeliveredToolResults.applicableIds(Vector(ut, running), ctx)
      ids shouldBe Set(running._id)
    }

    "fall back to the most-recent user task as the turn boundary when no claim is threaded" in Task {
      val oldTool = settledInvoke("old_grep", ts = 10L)
      val ut = userMsg("new task", ts = 100L)
      val newTool = settledInvoke("new_grep", ts = 110L)
      val ids = CompactionInvariant.UndeliveredToolResults.applicableIds(Vector(oldTool, ut, newTool), emptyCtx)
      // Prior turns' invokes stay compressible — only the active turn's
      // undelivered work is protected.
      ids shouldBe Set(newTool._id)
    }

    "protect nothing when no turn boundary can be established" in Task {
      val lone = settledInvoke("orphan", ts = 10L)
      CompactionInvariant.UndeliveredToolResults.applicableIds(Vector(lone), emptyCtx) shouldBe empty
    }
  }

  "CompactionInvariant.RecentTail" should {

    "protect the last n ids" in Task {
      val events: Vector[Event] = (1 to 6).map(i => toolInvoke(s"t-$i", ts = i.toLong)).toVector
      val ids = CompactionInvariant.RecentTail(3).applicableIds(events, emptyCtx)
      ids shouldBe events.takeRight(3).map(_._id).toSet
    }

    "protect everything when n >= size" in Task {
      val events: Vector[Event] = (1 to 3).map(i => toolInvoke(s"t-$i", ts = i.toLong)).toVector
      val ids = CompactionInvariant.RecentTail(10).applicableIds(events, emptyCtx)
      ids shouldBe events.map(_._id).toSet
    }

    "protect nothing when n is 0" in Task {
      val events: Vector[Event] = (1 to 3).map(i => toolInvoke(s"t-$i", ts = i.toLong)).toVector
      CompactionInvariant.RecentTail(0).applicableIds(events, emptyCtx) shouldBe empty
    }
  }

  "Composition" should {

    "union all invariants — anything in any set is protected" in Task {
      val ut = userMsg("user task", ts = 100L)
      val inv = toolInvoke("read", ts = 110L)
      val res = toolResult("ok", originId = inv._id, ts = 111L)
      val tail1 = toolInvoke("tail-1", ts = 200L)
      val tail2 = toolInvoke("tail-2", ts = 210L)
      val events: Vector[Event] = Vector(ut, inv, res, tail1, tail2)
      val ctx = emptyCtx.copy(claimedAt = Some(Timestamp(100L)))
      val invariants = List(
        CompactionInvariant.CurrentUserTaskMessage,
        CompactionInvariant.CurrentAgentClaimAnchor,
        CompactionInvariant.PairedToolResult(),
        CompactionInvariant.RecentTail(2)
      )
      val protectedIds = invariants.iterator.flatMap(_.applicableIds(events, ctx)).toSet
      // user task: from CurrentUserTaskMessage + claim anchor (same id)
      protectedIds should contain(ut._id)
      // paired tool exchange
      protectedIds should contain(inv._id)
      protectedIds should contain(res._id)
      // tail
      protectedIds should contain(tail1._id)
      protectedIds should contain(tail2._id)
    }

    "StandardIntraTurnCompactor.selectFoldable drops everything not in the protected union" in Task {
      // Default invariant set + recentTailN = 3. The older tool events
      // are marked DELIVERED (their results reached a consumed prompt in
      // a prior iteration) so UndeliveredToolResults releases them.
      val compactor = StandardIntraTurnCompactor(recentTailN = 3)
      val ut = userMsg("the task", ts = 50L)
      val older1 = settledInvoke("older-1", ts = 60L)
      val older2 = settledInvoke("older-2", ts = 70L)
      val tail1 = toolInvoke("tail-1", ts = 80L)
      val tail2 = toolInvoke("tail-2", ts = 90L)
      val tail3 = toolInvoke("tail-3", ts = 100L)
      val events: Vector[Event] = Vector(ut, older1, older2, tail1, tail2, tail3)
      val ctx = emptyCtx.copy(
        claimedAt = Some(Timestamp(50L)),
        deliveredToolResults = Set(older1._id, older2._id)
      )
      val folded = compactor.selectFoldable(events, ctx).toSet
      // ut protected by CurrentUserTaskMessage + claim anchor
      folded should not contain ut._id
      // tail protected by RecentTail
      folded should not contain tail1._id
      folded should not contain tail2._id
      folded should not contain tail3._id
      // older, DELIVERED agent-tool events are foldable
      folded should contain(older1._id)
      folded should contain(older2._id)
    }

    "selectFoldable never folds a settled-but-undelivered result, however old its timestamp (#418 field shape)" in Task {
      // The observed incident: ~8 fast greps (delivered across earlier
      // iterations), then a 14-minute sweep whose settle landed seconds
      // before the next iteration's compaction pass. By wall clock the
      // sweep invoke is among the OLDEST turn events — recency-based
      // selection folds it — but its result has never reached a prompt.
      val compactor = StandardIntraTurnCompactor(recentTailN = 2)
      val ut = userMsg("find and remove all references", ts = 100L)
      val greps = (1 to 8).map(i => settledInvoke(s"grep-$i", ts = 100L + i)).toVector
      val sweep = settledInvoke("refactor_with_instruction", ts = 200L, resultText = "Sweep converged. REQUIRED NEXT STEP: compile")
      val tailA = toolInvoke("tail-a", ts = 1000L)
      val tailB = toolInvoke("tail-b", ts = 1001L)
      val events: Vector[Event] = (ut +: greps) ++ Vector(sweep, tailA, tailB)
      val ctx = emptyCtx.copy(
        claimedAt = Some(Timestamp(100L)),
        deliveredToolResults = greps.map(_._id).toSet
      )
      val folded = compactor.selectFoldable(events, ctx).toSet
      // The delivered greps fold — compaction still works.
      greps.foreach(g => folded should contain(g._id))
      // The undelivered sweep result survives, despite being "old".
      folded should not contain sweep._id
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
