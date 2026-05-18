package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.event.{ErrorClassification, Message, MessageDisposition, ToolInvoke}
import sigil.signal.EventState
import sigil.tool.ToolName

/**
 * Regression for sigil bug #172 — boot-time reconciliation of events
 * left at `state = Active` by prior process exits (crash, OOM,
 * SIGKILL, container eviction). The framework's first boot pass after
 * DB open scans `db.events` and closes any Active rows:
 *
 *   - Messages → state Complete, disposition Failure(recoverable =
 *     false) with an ErrorContext explaining "stale from prior
 *     session".
 *   - Other Event types → state Complete via `.withState`.
 *
 * The reconciliation runs synchronously before WS / Notice ingress
 * opens — no live subscribers, no Delta noise. One bulk transaction
 * over the stale set.
 *
 * Test strategy: seed the DB directly with Active events (simulating
 * the post-crash state), then trigger reconciliation explicitly via
 * `TestSigil.runReconciliation()` (which delegates to the same
 * `reconcileStaleActiveEvents` path that `instance` invokes) and
 * inspect the row states.
 */
class StaleActiveEventReconciliationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("stale-active-conv")
  private val topicId = TestTopicEntry.id
  private val syntheticParent: Id[sigil.event.Event] = Id[sigil.event.Event]("stale-active-parent")

  private def seedConversation(): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
      _id = convId,
      topics = TestTopicStack
    )))).map(_ => ())

  private def insertEvent(e: sigil.event.Event): Task[Unit] =
    TestSigil.withDB(_.events.transaction(_.upsert(e))).map(_ => ())

  private def fetchEvent(id: Id[sigil.event.Event]): Task[Option[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.get(id)))

  "Boot-time reconciliation" should {

    "close stale Active Messages with Failure disposition + ErrorContext" in {
      val staleMessageId = Id[sigil.event.Event]("stale-msg-1")
      for {
        _ <- seedConversation()
        _ <- insertEvent(Message(
          _id = staleMessageId,
          participantId = TestUser,
          conversationId = convId,
          topicId = topicId,
          state = EventState.Active,
          content = Vector.empty
        ))
        _ <- TestSigil.runStaleActiveReconciliation()
        out <- fetchEvent(staleMessageId)
      } yield {
        val msg = out.collect { case m: Message => m }
          .getOrElse(fail(s"expected a Message at $staleMessageId"))
        msg.state shouldBe EventState.Complete
        msg.disposition shouldBe a[MessageDisposition.Failure]
        val failure = msg.disposition.asInstanceOf[MessageDisposition.Failure]
        failure.recoverable shouldBe false
        failure.errorContext shouldBe defined
        failure.errorContext.get.classification shouldBe ErrorClassification.FrameworkBug
        failure.errorContext.get.message should include("stale-from-previous-session")
      }
    }

    "close stale Active non-Message events with plain Complete (no disposition surface)" in {
      val staleInvokeId = Id[sigil.event.Event]("stale-invoke-1")
      for {
        _ <- seedConversation()
        _ <- insertEvent(ToolInvoke(
          _id = staleInvokeId,
          toolName = ToolName("respond"),
          participantId = TestUser,
          conversationId = convId,
          topicId = topicId,
          state = EventState.Active,
          origin = Some(syntheticParent)
        ))
        _ <- TestSigil.runStaleActiveReconciliation()
        out <- fetchEvent(staleInvokeId)
      } yield {
        val ti = out.collect { case t: ToolInvoke => t }
          .getOrElse(fail(s"expected a ToolInvoke at $staleInvokeId"))
        ti.state shouldBe EventState.Complete
        ti.toolName shouldBe ToolName("respond") // preserved
      }
    }

    "leave events that were already Complete untouched" in {
      val cleanMessageId = Id[sigil.event.Event]("clean-msg-1")
      val original = Message(
        _id = cleanMessageId,
        participantId = TestUser,
        conversationId = convId,
        topicId = topicId,
        state = EventState.Complete,
        content = Vector(sigil.tool.model.ResponseContent.Text("complete reply")),
        disposition = MessageDisposition.Success
      )
      for {
        _ <- seedConversation()
        _ <- insertEvent(original)
        _ <- TestSigil.runStaleActiveReconciliation()
        out <- fetchEvent(cleanMessageId)
      } yield {
        val msg = out.collect { case m: Message => m }
          .getOrElse(fail(s"expected a Message at $cleanMessageId"))
        msg.state shouldBe EventState.Complete
        msg.disposition shouldBe MessageDisposition.Success
      }
    }

    "be idempotent — running twice doesn't re-touch already-reconciled rows" in {
      val staleId = Id[sigil.event.Event]("stale-idempotent")
      for {
        _ <- seedConversation()
        _ <- insertEvent(Message(
          _id = staleId,
          participantId = TestUser,
          conversationId = convId,
          topicId = topicId,
          state = EventState.Active,
          content = Vector.empty
        ))
        _ <- TestSigil.runStaleActiveReconciliation()
        after1 <- fetchEvent(staleId)
        _ <- TestSigil.runStaleActiveReconciliation() // second pass — no-op
        after2 <- fetchEvent(staleId)
      } yield {
        // The second pass should leave the row identical to the first pass's output.
        after1 shouldBe after2
        after1.get.state shouldBe EventState.Complete
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
