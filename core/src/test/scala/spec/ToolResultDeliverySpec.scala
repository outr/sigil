package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ContextFrame, FrameBuilder, ToolCallState}
import sigil.event.{Event, Message, MessageRole, ToolInvoke, ToolOutcome}
import sigil.signal.EventState
import sigil.tool.{TextToolOutput, ToolName}
import sigil.tool.model.ResponseContent

/**
 * Delivery bookkeeping for settled tool results, plus the read-side
 * self-heal for fossilized race placeholders.
 *
 *   1. A ToolCall frame written while the invoke's outcome was still
 *      Pending (the "result raced past the prompt" marker) whose row
 *      HAS since settled is recomputed — and persisted — by
 *      [[sigil.Sigil.framesFor]], so a missed settle-time rewrite can
 *      never show the agent a stale placeholder for a result that
 *      exists.
 *   2. The per-conversation delivered-result registry accumulates ids
 *      and resets at the next user-turn boundary.
 */
class ToolResultDeliverySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConv(): Id[Conversation] = Conversation.id(s"delivery-${rapid.Unique()}")

  private def settledRowWithStaleFrame(convId: Id[Conversation], ts: Long): ToolInvoke = {
    // Step 1 — the invoke as the input-settle left it: Complete but
    // outcome Pending. Its computed frame carries the race placeholder.
    val pendingRow = ToolInvoke(
      toolName       = ToolName("slow_sweep"),
      participantId  = TestAgent,
      conversationId = convId,
      topicId        = TestTopicId,
      state          = EventState.Complete,
      timestamp      = Timestamp(ts)
    )
    val placeholderFrame = FrameBuilder.computeFrame(pendingRow)
    // Step 2 — the outcome has since settled on the row, but the frame
    // rewrite was missed (the fossilization the field exhibited: a
    // placeholder still in the prompt 14 minutes after completion).
    pendingRow
      .copy(output = TextToolOutput("sweep converged: 96 changed"), outcome = ToolOutcome.Success)
      .withContextFrame(placeholderFrame)
      .asInstanceOf[ToolInvoke]
  }

  "framesFor placeholder self-heal" should {

    "supersede a fossilized race placeholder with the row's real settled result" in {
      val convId = freshConv()
      val fossil = settledRowWithStaleFrame(convId, ts = 1000L)
      // Sanity: the stale frame IS the placeholder.
      fossil.contextFrame.collect { case tc: ContextFrame.ToolCall => tc } match {
        case Some(tc) =>
          tc.resultPending shouldBe true
          tc.state match {
            case ToolCallState.Complete(content, _) => content should include ("did not reach this turn")
            case other                              => fail(s"expected Complete placeholder, got $other")
          }
        case None => fail("fixture produced no ToolCall frame")
      }
      for {
        _      <- TestSigil.withDB(_.eventsTransaction(convId)(_.upsert(fossil)))
        frames <- TestSigil.framesFor(convId)
        // The heal also persisted — the durable row's frame is fixed.
        reread <- TestSigil.withDB(_.eventsTransaction(convId)(_.get(fossil._id)))
      } yield {
        val tc = frames.collectFirst { case t: ContextFrame.ToolCall => t }
          .getOrElse(fail("no ToolCall frame returned"))
        tc.resultPending shouldBe false
        tc.state match {
          case ToolCallState.Complete(content, _) =>
            content should include ("sweep converged: 96 changed")
            content should not include "did not reach this turn"
          case other => fail(s"expected healed Complete frame, got $other")
        }
        val persisted = reread.flatMap(_.contextFrame).collect { case t: ContextFrame.ToolCall => t }
          .getOrElse(fail("re-read row has no ToolCall frame"))
        persisted.resultPending shouldBe false
      }
    }

    "leave a genuinely still-pending invoke's placeholder in place" in {
      val convId = freshConv()
      val pendingRow = ToolInvoke(
        toolName       = ToolName("still_running"),
        participantId  = TestAgent,
        conversationId = convId,
        topicId        = TestTopicId,
        state          = EventState.Complete,
        timestamp      = Timestamp(1000L)
      )
      val row = pendingRow.withContextFrame(FrameBuilder.computeFrame(pendingRow))
      for {
        _      <- TestSigil.withDB(_.eventsTransaction(convId)(_.upsert(row)))
        frames <- TestSigil.framesFor(convId)
      } yield {
        val tc = frames.collectFirst { case t: ContextFrame.ToolCall => t }
          .getOrElse(fail("no ToolCall frame returned"))
        // The result truly hasn't arrived — the placeholder is the
        // honest rendering and stays.
        tc.resultPending shouldBe true
      }
    }
  }

  "the delivered-result registry" should {

    "accumulate marked ids and reset at the next user-turn boundary" in {
      val convId = freshConv()
      val idA = Id[Event]()
      val idB = Id[Event]()
      TestSigil.markToolResultsDelivered(convId, List(idA))
      TestSigil.markToolResultsDelivered(convId, List(idB))
      TestSigil.deliveredToolResultIds(convId) shouldBe Set(idA, idB)
      // A fresh user-authored Message is the turn boundary.
      for {
        _ <- TestSigil.publish(Message(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          role           = MessageRole.Standard,
          content        = Vector(ResponseContent.Text("next task")),
          state          = EventState.Complete
        ))
      } yield {
        TestSigil.deliveredToolResultIds(convId) shouldBe empty
      }
    }

    "keep tracking scoped per conversation" in {
      val convA = freshConv()
      val convB = freshConv()
      val id = Id[Event]()
      TestSigil.markToolResultsDelivered(convA, List(id))
      TestSigil.deliveredToolResultIds(convA) shouldBe Set(id)
      TestSigil.deliveredToolResultIds(convB) shouldBe empty
      Task.unit.map(_ => succeed)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
