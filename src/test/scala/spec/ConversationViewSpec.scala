package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ActiveSkillSlot, ContextFrame, ContextKey, ContextSummary, Conversation, ConversationView, SkillSource, Topic}
import sigil.event.{Event, Message, ModeChange, TopicChange, TopicChangeKind}
import sigil.provider.Mode
import sigil.signal.{EventState, MessageDelta, StateDelta}
import sigil.tool.model.{ChangeModeInput, ResponseContent}

/**
 * Integration coverage for the [[ConversationView]] materialization path:
 *   - `Sigil.publish(event)` updates the view inside the same transaction
 *     that writes the event
 *   - Active events don't become frames; they do once they transition to
 *     Complete via a delta
 *   - `Sigil.rebuildView` reproduces an equivalent view by replaying the
 *     event log
 *   - `Sigil.persistSummary` + `Sigil.summariesFor` round-trip
 *
 * No live LLM — purely framework plumbing.
 */
class ConversationViewSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConvId(suffix: String): Id[Conversation] =
    Conversation.id(s"view-spec-$suffix-${rapid.Unique()}")

  "Sigil.publish + ConversationView" should {
    "append a Text frame when an atomic Complete Message is published" in {
      val convId = freshConvId("atomic-msg")
      val msg = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("hello from user")),
        state = EventState.Complete
      )
      for {
        _ <- TestSigil.publish(msg)
        view <- TestSigil.viewFor(convId)
      } yield {
        view.frames should have size 1
        view.frames.head shouldBe a[ContextFrame.Text]
        view.frames.head.asInstanceOf[ContextFrame.Text].content shouldBe "hello from user"
      }
    }

    "not add a frame for an Active Message until a completing delta arrives" in {
      val convId = freshConvId("streaming")
      val msg = Message(
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector.empty,
        state = EventState.Active
      )
      for {
        _ <- TestSigil.publish(msg)
        viewMid <- TestSigil.viewFor(convId)
        _ <- TestSigil.publish(MessageDelta(
          target = msg._id,
          conversationId = convId,
          content = Some(sigil.signal.ContentDelta(
            kind = sigil.signal.ContentKind.Text,
            arg = None,
            complete = true,
            delta =
              "settled content"
          )),
          state = Some(EventState.Complete)
        ))
        viewEnd <- TestSigil.viewFor(convId)
      } yield {
        viewMid.frames shouldBe empty
        viewEnd.frames should have size 1
        viewEnd.frames.head.asInstanceOf[ContextFrame.Text].content shouldBe "settled content"
      }
    }

    "be idempotent — a repeated publish for the same event doesn't duplicate its frame" in {
      val convId = freshConvId("idempotent")
      val msg = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("once")),
        state = EventState.Complete
      )
      for {
        _ <- TestSigil.publish(msg)
        _ <- TestSigil.publish(StateDelta(target = msg._id, conversationId = convId, state = EventState.Complete))
        view <- TestSigil.viewFor(convId)
      } yield view.frames.count(_.sourceEventId == msg._id) shouldBe 1
    }

    "match incremental build and full rebuild for the same event sequence" in {
      val convId = freshConvId("rebuild")
      val first = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("first")),
        state = EventState.Complete
      )
      val second = Message(
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("second")),
        state = EventState.Complete
      )
      for {
        _ <- TestSigil.publish(first)
        _ <- TestSigil.publish(second)
        incremental <- TestSigil.viewFor(convId)
        rebuilt <- TestSigil.rebuildView(convId)
      } yield {
        incremental.frames.map(_.sourceEventId) shouldBe rebuilt.frames.map(_.sourceEventId)
        incremental.frames.size shouldBe 2
      }
    }
  }

  "Sigil projection-mutation API" should {
    "set and clear a skill slot via activateSkill / clearSkill" in {
      val convId = freshConvId("skill-activate")
      val slot = ActiveSkillSlot(name = "weather", content = "Use Celsius.")
      for {
        _ <- TestSigil.activateSkill(convId, TestAgent, SkillSource.Discovery, slot)
        after <- TestSigil.viewFor(convId)
        _ <- TestSigil.clearSkill(convId, TestAgent, SkillSource.Discovery)
        cleared <- TestSigil.viewFor(convId)
      } yield {
        after.projectionFor(TestAgent).activeSkills(SkillSource.Discovery) shouldBe slot
        cleared.projectionFor(TestAgent).activeSkills.get(SkillSource.Discovery) shouldBe None
      }
    }

    "set and clear per-participant extraContext via setParticipantContext / clearParticipantContext" in {
      val convId = freshConvId("extra-context")
      val key = ContextKey("mood")
      for {
        _ <- TestSigil.setParticipantContext(convId, TestAgent, key, "optimistic")
        after <- TestSigil.viewFor(convId)
        _ <- TestSigil.clearParticipantContext(convId, TestAgent, key)
        cleared <- TestSigil.viewFor(convId)
      } yield {
        after.projectionFor(TestAgent).extraContext(key) shouldBe "optimistic"
        cleared.projectionFor(TestAgent).extraContext.get(key) shouldBe None
      }
    }

    "apply a Mode-source skill via the modeSkill hook when a ModeChange completes" in {
      val convId = freshConvId("mode-skill")
      val slot = ActiveSkillSlot(name = "coding", content = "Prefer Scala 3 syntax.")
      TestSigil.setModeSkill {
        case Mode.Coding => rapid.Task.pure(Some(slot))
        case _ => rapid.Task.pure(None)
      }
      val mc = ModeChange(
        mode = Mode.Coding,
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicId,
        state = EventState.Complete
      )
      for {
        _ <- TestSigil.publish(mc)
        afterCoding <- TestSigil.viewFor(convId)
        _ <- TestSigil.publish(ModeChange(
          mode = Mode.Conversation,
          participantId = TestAgent,
          conversationId = convId,
          topicId = TestTopicId,
          state =
            EventState.Complete
        ))
        afterConversation <- TestSigil.viewFor(convId)
        _ <- rapid.Task(TestSigil.resetModeSkill())
      } yield {
        afterCoding.projectionFor(TestAgent).activeSkills(SkillSource.Mode) shouldBe slot
        // Mode-source slot cleared when the hook returns None for the new mode.
        afterConversation.projectionFor(TestAgent).activeSkills.get(SkillSource.Mode) shouldBe None
      }
    }
  }

  "Active → Complete lifecycle" should {
    "settle an Active event to Complete via a StateDelta published afterward" in {
      val convId = freshConvId("lifecycle-settle")
      val pulse = ModeChange(
        mode = Mode.Coding,
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicId
      )
      // Pulse defaults to Active
      pulse.state shouldBe EventState.Active

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(currentTopicId = TestTopicId, _id = convId))))
        _ <- TestSigil.publish(pulse)
        // Between pulse and settle, the DB reflects Active.
        mid <- TestSigil.withDB(_.events.transaction(_.get(pulse._id)))
        _ <- TestSigil.publish(StateDelta(
          target = pulse._id,
          conversationId = convId,
          state = EventState.Complete
        ))
        // After the settle, the DB reflects Complete.
        after <- TestSigil.withDB(_.events.transaction(_.get(pulse._id)))
        // The view projection records the Complete settle (currentMode
        // updated). Verifies `updateConversationProjection`'s
        // Complete-only filter on the delta path.
        conv <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        mid.map(_.state) shouldBe Some(EventState.Active)
        after.map(_.state) shouldBe Some(EventState.Complete)
        // currentMode only updates once the settle lands, not on the pulse.
        // (The pulse publish ran first; currentMode was not touched until
        // the StateDelta published.)
        conv.map(_.currentMode) shouldBe Some(Mode.Coding)
      }
    }

    "not double-write the Conversation projection on pulse + settle" in {
      // Publish a pulse (Active); conversation.currentMode should NOT
      // update yet (projection filters to Complete only). Then settle;
      // it should update exactly once.
      val convId = freshConvId("lifecycle-nodouble")
      // Start conversation in a known initial mode so we can detect any
      // stray write.
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
          Conversation(currentTopicId = TestTopicId, _id = convId, currentMode = Mode.Conversation)
        )))
        pulse = ModeChange(mode = Mode.Coding, participantId = TestAgent, conversationId = convId, topicId = TestTopicId)
        _ <- TestSigil.publish(pulse)
        afterPulseOnly <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
        _ <- TestSigil.publish(StateDelta(
          target = pulse._id,
          conversationId = convId,
          state = EventState.Complete
        ))
        afterSettle <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        // Pulse alone must not have shifted currentMode — the settle owns
        // that write.
        afterPulseOnly.map(_.currentMode) shouldBe Some(Mode.Conversation)
        afterSettle.map(_.currentMode) shouldBe Some(Mode.Coding)
      }
    }
  }

  "TopicChange → Conversation.currentTopicId" should {
    "update currentTopicId when a Complete TopicChange(Switch) settles" in {
      val convId = freshConvId("topic-switch-settle")
      val initialTopicId = Topic.id(s"initial-${rapid.Unique()}")
      val newTopicId = Topic.id(s"new-${rapid.Unique()}")
      val conv = Conversation(currentTopicId = initialTopicId, _id = convId)
      val tc = TopicChange(
        kind = TopicChangeKind.Switch(previousTopicId = initialTopicId),
        newLabel = "Shifted Subject",
        participantId = TestAgent,
        conversationId = convId,
        topicId = newTopicId,
        state = EventState.Complete
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(tc)
        after <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield after.map(_.currentTopicId) shouldBe Some(newTopicId)
    }

    "leave currentTopicId unchanged when a TopicChange(Rename) settles (Rename mutates Topic in-place)" in {
      val convId = freshConvId("topic-rename-settle")
      val topicId = Topic.id(s"rename-${rapid.Unique()}")
      val conv = Conversation(currentTopicId = topicId, _id = convId)
      val tc = TopicChange(
        kind = TopicChangeKind.Rename(previousLabel = "Old Label"),
        newLabel = "New Label",
        participantId = TestAgent,
        conversationId = convId,
        topicId = topicId,
        state = EventState.Complete
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(tc)
        after <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield after.map(_.currentTopicId) shouldBe Some(topicId)
    }
  }

  "Sigil.persistSummary / summariesFor" should {
    "round-trip a summary through the dedicated summaries store" in {
      val convId = freshConvId("summary")
      val summary = ContextSummary(
        text = "Earlier: user asked about X; agent explained Y.",
        conversationId = convId,
        tokenEstimate = 24
      )
      for {
        _ <- TestSigil.persistSummary(summary)
        fetched <- TestSigil.summariesFor(convId)
      } yield {
        fetched.map(_._id) shouldBe List(summary._id)
        fetched.head.text should include("user asked about X")
      }
    }

    "return summaries oldest-first when multiple exist" in {
      val convId = freshConvId("summary-order")
      val older = ContextSummary(text = "OLDER", conversationId = convId, tokenEstimate = 1)
      val newer = ContextSummary(
        text = "NEWER",
        conversationId = convId,
        tokenEstimate = 1,
        created = lightdb.time.Timestamp(lightdb.util.Nowish() + 1000))
      for {
        _ <- TestSigil.persistSummary(older)
        _ <- TestSigil.persistSummary(newer)
        fetched <- TestSigil.summariesFor(convId)
      } yield fetched.map(_.text) shouldBe List("OLDER", "NEWER")
    }
  }
}
