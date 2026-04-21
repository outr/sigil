package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, ContextSummary, Conversation, ConversationView}
import sigil.event.{Event, Message}
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
        view.lastEventId shouldBe Some(msg._id)
      }
    }

    "not add a frame for an Active Message until a completing delta arrives" in {
      val convId = freshConvId("streaming")
      val msg = Message(
        participantId = TestAgent,
        conversationId = convId,
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
            delta = "settled content"
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
        content = Vector(ResponseContent.Text("once")),
        state = EventState.Complete
      )
      for {
        _ <- TestSigil.publish(msg)
        _ <- TestSigil.publish(StateDelta(target = msg._id, conversationId = convId, state = EventState.Complete))
        view <- TestSigil.viewFor(convId)
      } yield {
        view.frames.count(_.sourceEventId == msg._id) shouldBe 1
      }
    }

    "match incremental build and full rebuild for the same event sequence" in {
      val convId = freshConvId("rebuild")
      val first = Message(
        participantId = TestUser,
        conversationId = convId,
        content = Vector(ResponseContent.Text("first")),
        state = EventState.Complete
      )
      val second = Message(
        participantId = TestAgent,
        conversationId = convId,
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
      val newer = ContextSummary(text = "NEWER", conversationId = convId, tokenEstimate = 1,
                                  created = lightdb.time.Timestamp(lightdb.util.Nowish() + 1000))
      for {
        _ <- TestSigil.persistSummary(older)
        _ <- TestSigil.persistSummary(newer)
        fetched <- TestSigil.summariesFor(convId)
      } yield {
        fetched.map(_.text) shouldBe List("OLDER", "NEWER")
      }
    }
  }
}
