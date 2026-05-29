package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ContextSummary, Topic, TopicEntry}
import sigil.conversation.compression.{StandardIntraTurnCompactor, StandardContextCurator}
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent

/**
 * Regression for sigil bug #285 — intra-turn compaction at iteration
 * boundaries within a single user turn. Covers:
 *
 *   1. [[StandardIntraTurnCompactor.shouldCompact]] triggers on size
 *      pressure (estimated tokens >= threshold).
 *   2. [[StandardIntraTurnCompactor.shouldCompact]] triggers on a
 *      natural boundary (post-`respond` standard-role Message).
 *   3. [[StandardIntraTurnCompactor.shouldCompact]] triggers on a
 *      terminal-tool ToolInvoke when the apps wires that tool into
 *      `terminalTools`.
 *   4. [[StandardIntraTurnCompactor.selectFoldable]] keeps the most-
 *      recent `keepRecent` events out of the fold list.
 *   5. The curator's frame filter excludes events whose id is in any
 *      persisted [[ContextSummary]]'s `coversEventIds`, so the next
 *      iteration's wire prompt sees the summary text in place of the
 *      folded events.
 */
class IntraTurnCompactionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId: Id[Conversation] = Conversation.id(s"compact-spec-${rapid.Unique()}")

  private def msg(text: String, role: MessageRole = MessageRole.Standard): Message =
    Message(
      participantId  = TestUser,
      conversationId = convId,
      topicId        = TestTopicId,
      role           = role,
      content        = Vector(ResponseContent.Text(text)),
      state          = EventState.Complete
    )

  private def toolInvoke(name: String): ToolInvoke =
    ToolInvoke(
      toolName       = ToolName(name),
      participantId  = TestAgent,
      conversationId = convId,
      topicId        = TestTopicId,
      state          = EventState.Complete
    )

  "StandardIntraTurnCompactor (sigil #285)" should {

    "fire on size pressure when estimated tokens >= threshold" in Task {
      val compactor = StandardIntraTurnCompactor()
      val events = (1 to 10).map(i => msg(s"event-$i")).toVector
      compactor.shouldCompact(events, estimatedTokens = 1000L, threshold = 500L) shouldBe true
    }

    "fire after a standard-role respond Message even under threshold" in Task {
      val compactor = StandardIntraTurnCompactor()
      val events = (1 to 5).map(i => toolInvoke(s"read-$i")).toVector :+ msg("here is my reply")
      compactor.shouldCompact(events, estimatedTokens = 10L, threshold = Long.MaxValue) shouldBe true
    }

    "fire after a terminal-tool ToolInvoke when apps wire one" in Task {
      val compactor = StandardIntraTurnCompactor(terminalTools = Set(ToolName("preview_theme")))
      val events = (1 to 5).map(i => toolInvoke(s"read-$i")).toVector :+ toolInvoke("preview_theme")
      compactor.shouldCompact(events, estimatedTokens = 10L, threshold = Long.MaxValue) shouldBe true
    }

    "NOT fire when below threshold AND last event is a tool call (no natural boundary)" in Task {
      val compactor = StandardIntraTurnCompactor()
      val events = (1 to 6).map(i => toolInvoke(s"read-$i")).toVector
      compactor.shouldCompact(events, estimatedTokens = 50L, threshold = 5000L) shouldBe false
    }

    "NOT fire when events <= keepRecent (nothing to fold safely)" in Task {
      val compactor = StandardIntraTurnCompactor(keepRecent = 4)
      val events = (1 to 4).map(i => msg(s"e-$i")).toVector
      compactor.shouldCompact(events, estimatedTokens = 100_000L, threshold = 1L) shouldBe false
    }

    "selectFoldable keeps the most-recent keepRecent events out of the fold list" in Task {
      val compactor = StandardIntraTurnCompactor(keepRecent = 3)
      // Tool invokes are foldable; user-authored Standard Messages
      // are not (sigil #307), so the keepRecent semantics get tested
      // cleanly with a tool-only fixture.
      val events: Vector[Event] = (1 to 10).map(i => toolInvoke(s"read-$i")).toVector
      val folded = compactor.selectFoldable(events)
      folded.size shouldBe 7
      folded shouldBe events.take(7).map(_._id).toList
      // Last 3 NOT in the fold list.
      val foldSet = folded.toSet
      events.takeRight(3).forall(e => !foldSet.contains(e._id)) shouldBe true
    }

    "selectFoldable returns Nil when events <= keepRecent" in Task {
      val compactor = StandardIntraTurnCompactor(keepRecent = 4)
      compactor.selectFoldable((1 to 3).map(i => toolInvoke(s"read-$i")).toVector) shouldBe Nil
    }
  }

  "StandardContextCurator (sigil #285)" should {

    "filter frames whose source event id is in any persisted summary's coversEventIds" in {
      // Seed: a topic, three Message events (we'll cover the middle one).
      val topic = TopicEntry(TestTopicId, "test", "test")
      val conv  = Conversation(_id = convId, topics = List(topic))
      val e1 = msg("first")
      val e2 = msg("middle (to be covered)")
      val e3 = msg("third")
      val summary = ContextSummary(
        text           = "we did some middle work",
        conversationId = convId,
        tokenEstimate  = 8,
        coversEventIds = List(e2._id)
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(e1)
        _ <- TestSigil.publish(e2)
        _ <- TestSigil.publish(e3)
        _ <- TestSigil.persistSummary(summary)
        // Drive the curator and inspect resulting frames.
        defaultModel = TestSigil.defaultTestModel
        turn <- StandardContextCurator(TestSigil).curate(convId, defaultModel._id, List(TestUser))
      } yield {
        val ids = turn.frames.map(_.sourceEventId).toSet
        ids should contain (e1._id)
        ids should contain (e3._id)
        ids should not contain e2._id
        turn.summaries should contain (summary._id)
      }
    }
  }

  "StandardIntraTurnCompactor (sigil #307)" should {

    "NEVER fold a user-authored Standard-role Message even when the slice covers it" in Task {
      // The "claim survives across user turns" path. User posts a task
      // mid-loop; the compactor's slice (events since the agent's
      // claim) now contains BOTH old tool exchanges AND the new user
      // task. Before the fix selectFoldable folded everything except
      // the last keepRecent, including the user task message — the
      // model saw only `(begin conversation)` + recent tool exchanges
      // and emitted a fresh greeting. The invariant the fix enforces:
      // user-authored Standard-role Messages are never compacted.
      val compactor = StandardIntraTurnCompactor(keepRecent = 3)
      val turnEvents: Vector[Event] = Vector(
        toolInvoke("read-1"),
        toolInvoke("read-2"),
        toolInvoke("read-3"),
        msg("I'd like you to find and remove all bug references"),   // user task
        toolInvoke("read-4"),
        toolInvoke("read-5"),
        toolInvoke("read-6"),
        toolInvoke("read-7"),
        toolInvoke("read-8"),
        toolInvoke("read-9")
      )
      val folded = compactor.selectFoldable(turnEvents).toSet
      val userTaskId = turnEvents(3)._id
      folded should not contain userTaskId
      // The other turn-events DO get folded (everything not in
      // keepRecent's tail and not a user-authored Standard Message).
      folded should contain (turnEvents(0)._id)
      folded should contain (turnEvents(1)._id)
      folded should contain (turnEvents(2)._id)
    }

    "fold agent-authored Standard-role Messages normally (only user-authored is protected)" in Task {
      // The protection is "Standard-role from a NON-agent participant"
      // — the agent's own responses are still foldable.
      val agentMsg = Message(
        participantId  = TestAgent,
        conversationId = convId,
        topicId        = TestTopicId,
        role           = MessageRole.Standard,
        content        = Vector(ResponseContent.Text("here is my reply")),
        state          = EventState.Complete
      )
      val compactor = StandardIntraTurnCompactor(keepRecent = 2)
      val turnEvents: Vector[Event] = Vector(
        toolInvoke("read-1"),
        agentMsg,
        toolInvoke("read-2"),
        toolInvoke("read-3")
      )
      val folded = compactor.selectFoldable(turnEvents).toSet
      folded should contain (agentMsg._id)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
