package spec

import fabric.str
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, TopicEntry}
import sigil.event.Event
import sigil.maintenance.ConversationContainerCleanupTask
import sigil.tool.output.ToolOutputNode

import scala.concurrent.duration.*

/**
 * Tool output is a durable point-in-time observation, not a regenerable
 * cache, so by default it never expires by time — it lives for the
 * conversation's lifetime. This locks that default (`ageWindow = None`),
 * the opt-in age eviction, and the size backstop that stays active
 * regardless.
 */
class ConversationContainerCleanupSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConv(suffix: String): Task[Conversation] = {
    val conv = Conversation(
      topics = List(TopicEntry(TestTopicId, "t", "t")),
      _id    = Conversation.id(s"ccc-$suffix-${rapid.Unique()}")
    )
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def node(convId: Id[Conversation], createdMillis: Long, callId: Id[Event] = Event.id()): ToolOutputNode =
    ToolOutputNode(
      conversationId = convId,
      callId         = callId,
      referenceId    = callId.value,
      level          = 0,
      ordinal        = 0,
      hasChildren    = false,
      payload        = str("x"),
      created        = Timestamp(createdMillis)
    )

  private def rowsIn(convId: Id[Conversation]): Task[Int] =
    TestSigil.withDB(_.toolOutputs.transaction(_.list)).map(_.count(_.conversationId == convId))

  "ConversationContainerCleanupTask" should {
    "never evict by time when ageWindow is None (TTL forever — the default)" in {
      for {
        conv <- freshConv("forever")
        _    <- TestSigil.withDB(_.toolOutputs.transaction(_.upsert(node(conv._id, createdMillis = 1000L))))
        _    <- ConversationContainerCleanupTask(ageWindow = None, sizeLimit = 100000).runOnce(TestSigil)
        n    <- rowsIn(conv._id)
      } yield n shouldBe 1  // ancient row survives — no time eviction
    }

    "evict aged containers when ageWindow is Some" in {
      for {
        conv <- freshConv("aged")
        _    <- TestSigil.withDB(_.toolOutputs.transaction(_.upsert(node(conv._id, createdMillis = 1000L))))
        // conv.modified defaults to ~now; a 1-ms window puts the ancient
        // row well outside it.
        _    <- ConversationContainerCleanupTask(ageWindow = Some(1.milli), sizeLimit = 100000).runOnce(TestSigil)
        n    <- rowsIn(conv._id)
      } yield n shouldBe 0
    }

    "prune oldest containers FIFO over the size cap even with age off" in {
      for {
        conv <- freshConv("size")
        _    <- Task.sequence((0 until 5).toList.map { i =>
                  TestSigil.withDB(_.toolOutputs.transaction(_.upsert(node(conv._id, createdMillis = 1000L + i))))
                })
        _    <- ConversationContainerCleanupTask(ageWindow = None, sizeLimit = 3).runOnce(TestSigil)
        n    <- rowsIn(conv._id)
      } yield n shouldBe 3  // two oldest containers pruned to fit the cap
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
