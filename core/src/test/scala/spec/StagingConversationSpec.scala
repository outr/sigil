package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextMemory, ContextSummary, Conversation, MemorySource, Topic, TopicEntry}
import sigil.event.Message
import sigil.signal.{ConversationHistoryImported, EventState, Signal}
import sigil.tool.model.ResponseContent
import sigil.{GlobalSpace, SpaceId}

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

/**
 * Coverage for the staging-conversation merge primitives that
 * underpin long-running data imports:
 *
 *   - `createStagingConversation` — opens a Conversation row marked
 *     with `stagingFor = Some(target)`.
 *   - `publishHistoricalSilent` — persists chunks into the staging
 *     conv WITHOUT firing the `ConversationHistoryImported` Notice.
 *   - `mergeStagingIntoMain` — flips events / memories / summaries
 *     from the staging convId to the target, deletes the staging
 *     row, and emits one Notice.
 *   - `deleteStagingConversation` — explicit cancel-path cleanup.
 */
class StagingConversationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val stagingSpace: SpaceId = GlobalSpace

  private def freshTargetConversation(label: String): Task[Conversation] = {
    val convId = Conversation.id(s"target-$label-${rapid.Unique()}")
    val topic  = TopicEntry(id = Topic.id(s"topic-$convId"), label = "test", summary = "test")
    val conv   = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def buildEvents(targetTopicId: Id[Topic],
                          stagingConvId: Id[Conversation],
                          count: Int): Seq[Message] =
    (0 until count).map { i =>
      Message(
        participantId  = TestUser,
        conversationId = stagingConvId,
        topicId        = targetTopicId,
        content        = Vector(ResponseContent.Text(s"hello $i")),
        state          = EventState.Complete
      )
    }

  /** Subscribe to `signals` for the duration of `body`, returning
    * every Signal observed. */
  private def captureSignals[A](body: Task[A]): Task[(A, List[Signal])] = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running  = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    for {
      _      <- Task.sleep(50.millis)
      result <- body
      _      <- Task.sleep(150.millis)
    } yield {
      running.set(false)
      (result, recorded.iterator().asScala.toList)
    }
  }

  "publishHistoricalSilent" should {
    "persist events without firing ConversationHistoryImported" in {
      for {
        target <- freshTargetConversation("silent")
        stagingId  = Conversation.id(s"staging-${rapid.Unique()}")
        _      <- TestSigil.createStagingConversation(stagingId, stagingFor = target._id)
        events = buildEvents(target.currentTopicId, stagingId, count = 20)
        captured <- captureSignals(TestSigil.publishHistoricalSilent(events, stagingId))
        (_, signals) = captured
        listed <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        // No ConversationHistoryImported emitted by the silent call.
        signals.collect { case n: ConversationHistoryImported => n } shouldBe empty
        // But the events ARE persisted under the staging conv.
        listed.count(_.conversationId == stagingId) shouldBe 20
      }
    }
  }

  "mergeStagingIntoMain" should {
    "rewrite event conversationId and emit one ConversationHistoryImported against the target" in {
      for {
        target <- freshTargetConversation("merge-events")
        stagingId  = Conversation.id(s"staging-${rapid.Unique()}")
        _      <- TestSigil.createStagingConversation(stagingId, stagingFor = target._id)
        events = buildEvents(target.currentTopicId, stagingId, count = 30)
        _      <- TestSigil.publishHistoricalSilent(events, stagingId)
        captured <- captureSignals(TestSigil.mergeStagingIntoMain(stagingId, target._id))
        (count, signals) = captured
        listedAfter <- TestSigil.withDB(_.events.transaction(_.list))
        stagingRowAfter <- TestSigil.withDB(_.conversations.transaction(_.get(stagingId)))
      } yield {
        count shouldBe 30
        signals.collect {
          case n: ConversationHistoryImported if n.conversationId == target._id => n
        } should have size 1
        // Every event now points at target; none at staging.
        listedAfter.count(_.conversationId == target._id) should be >= 30
        listedAfter.count(_.conversationId == stagingId)  shouldBe 0
        // Staging conversation row deleted.
        stagingRowAfter shouldBe None
      }
    }

    "rewrite ContextMemory + ContextSummary conversationIds during the merge" in {
      for {
        target <- freshTargetConversation("merge-records")
        stagingId  = Conversation.id(s"staging-${rapid.Unique()}")
        _      <- TestSigil.createStagingConversation(stagingId, stagingFor = target._id)
        memory <- TestSigil.persistMemory(ContextMemory(
                    fact = "Imported fact",
                    label = "Imported fact",
                    summary = "Imported fact",
                    source = MemorySource.Explicit,
                    spaceId = stagingSpace,
                    conversationId = Some(stagingId)
                  ))
        summary <- TestSigil.withDB(_.summaries.transaction(_.upsert(ContextSummary(
                     conversationId = stagingId,
                     text = "Imported summary",
                     tokenEstimate = 0
                   ))))
        _ <- TestSigil.mergeStagingIntoMain(stagingId, target._id)
        loadedMemory <- TestSigil.withDB(_.memories.transaction(_.get(memory._id)))
        loadedSummary <- TestSigil.withDB(_.summaries.transaction(_.get(summary._id)))
      } yield {
        loadedMemory.flatMap(_.conversationId) shouldBe Some(target._id)
        loadedSummary.map(_.conversationId)    shouldBe Some(target._id)
      }
    }
  }

  "deleteStagingConversation" should {
    "drop events / memories / summaries and the staging conversation row, with no Notice" in {
      for {
        target <- freshTargetConversation("cancel")
        stagingId  = Conversation.id(s"staging-${rapid.Unique()}")
        _      <- TestSigil.createStagingConversation(stagingId, stagingFor = target._id)
        events = buildEvents(target.currentTopicId, stagingId, count = 10)
        _      <- TestSigil.publishHistoricalSilent(events, stagingId)
        memory <- TestSigil.persistMemory(ContextMemory(
                    fact = "Imported fact",
                    label = "Imported fact",
                    summary = "Imported fact",
                    source = MemorySource.Explicit,
                    spaceId = stagingSpace,
                    conversationId = Some(stagingId)
                  ))
        _ <- TestSigil.withDB(_.summaries.transaction(_.upsert(ContextSummary(
               conversationId = stagingId,
               text = "Imported summary",
               tokenEstimate = 0
             ))))
        captured <- captureSignals(TestSigil.deleteStagingConversation(stagingId))
        (_, signals) = captured
        eventsAfter <- TestSigil.withDB(_.events.transaction(_.list))
        memoryAfter <- TestSigil.withDB(_.memories.transaction(_.get(memory._id)))
        stagingRowAfter <- TestSigil.withDB(_.conversations.transaction(_.get(stagingId)))
      } yield {
        signals.collect { case n: ConversationHistoryImported => n } shouldBe empty
        eventsAfter.count(_.conversationId == stagingId) shouldBe 0
        memoryAfter shouldBe None
        stagingRowAfter shouldBe None
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
