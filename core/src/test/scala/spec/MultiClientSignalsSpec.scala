package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ContextMemory, ContextKey, MemorySource, MemoryType, Topic, TopicEntry}
import sigil.signal.{
  MemoryListSnapshot, ModelCatalogSnapshot, ParticipantProjectionUpdated,
  RequestMemoryList, RequestModelCatalog, Signal
}
import sigil.spatial.Place
import sigil.tool.model.MemoryListEntry
import spice.net.URL

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Regression suite for the three "UI signal" bugs in one place — they
 * share fixtures (a viewer, a conversation, a few persisted memories)
 * and the same subscribe/snapshot pattern.
 *
 *   - sigil #291 — `ParticipantProjectionUpdated` fires when an app
 *     writes through `updateProjection`/`setParticipantContext`;
 *     suppressible via `broadcast = false` for framework-internal
 *     cache writes (`setProviderResponseState`).
 *   - sigil #292 — `RequestMemoryList` produces a `MemoryListSnapshot`
 *     scoped to the viewer's authored memories with filter
 *     application.
 *   - sigil #293 — `RequestModelCatalog` produces a
 *     `ModelCatalogSnapshot` over the global `cache.all` registry with
 *     filter application.
 */
class MultiClientSignalsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def subscribe(viewer: sigil.participant.ParticipantId): (ConcurrentLinkedQueue[Signal], () => Unit) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    @volatile var running = true
    TestSigil.signalsFor(viewer)
      .evalMap(s => Task { recorded.add(s); () })
      .takeWhile(_ => running)
      .drain
      .startUnit()
    (recorded, () => running = false)
  }

  private def collect[T <: Signal](q: ConcurrentLinkedQueue[Signal])(implicit ct: scala.reflect.ClassTag[T]): List[T] = {
    import scala.jdk.CollectionConverters.*
    q.iterator().asScala.toList.collect { case s if ct.runtimeClass.isInstance(s) => s.asInstanceOf[T] }
  }

  "RequestMemoryList (sigil #292)" should {

    "filter to memories the viewer authored AND match the query/pinned/hasLocation predicates" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        // Three memories: two by TestUser (one pinned, one with
        // location), one by TestAgent (must be excluded from
        // TestUser's view).
        _ <- TestSigil.persistMemory(ContextMemory(
          fact = "User favorite color is amber",
          label = "color",
          summary = "amber",
          source = MemorySource.Explicit,
          spaceId = TestSpace,
          pinned = true,
          createdBy = Some(TestUser)
        ))
        _ <- TestSigil.persistMemory(ContextMemory(
          fact = "User lives in Seattle",
          label = "location",
          summary = "Seattle WA",
          source = MemorySource.Explicit,
          spaceId = TestSpace,
          createdBy = Some(TestUser),
          location = Some(Place(point = lightdb.spatial.Point(47.6, -122.3)))
        ))
        _ <- TestSigil.persistMemory(ContextMemory(
          fact = "Agent observed user prefers terse replies",
          label = "style",
          summary = "terse",
          source = MemorySource.Compression,
          spaceId = TestSpace,
          createdBy = Some(TestAgent)
        ))
        // Unfiltered request — TestUser's two memories should land,
        // TestAgent's memory should not.
        _ <- TestSigil.handleNotice(RequestMemoryList(), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        val snaps = collect[MemoryListSnapshot](recorded)
        snaps should not be empty
        val first = snaps.head
        first.memories.size shouldBe 2
        first.memories.forall(_.spaceId == TestSpace.value) shouldBe true
      }
    }

    "narrow by `pinned = Some(true)`" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.handleNotice(RequestMemoryList(pinned = Some(true)), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        val snaps = collect[MemoryListSnapshot](recorded)
        val first = snaps.head
        first.memories.size shouldBe 1
        first.memories.head.pinned shouldBe true
      }
    }

    "narrow by `hasLocation = true`" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.handleNotice(RequestMemoryList(hasLocation = true), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        val snaps = collect[MemoryListSnapshot](recorded)
        val first = snaps.head
        first.memories.size shouldBe 1
        first.memories.head.label shouldBe "location"
      }
    }

    "narrow by case-insensitive substring `query`" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.handleNotice(RequestMemoryList(query = Some("AMBER")), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        val snaps = collect[MemoryListSnapshot](recorded)
        val first = snaps.head
        first.memories.size shouldBe 1
        first.memories.head.summary should include("amber")
      }
    }
  }

  "RequestModelCatalog (sigil #293)" should {

    "return every model in cache when no filter is set" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.handleNotice(RequestModelCatalog(), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        val snaps = collect[ModelCatalogSnapshot](recorded)
        snaps should not be empty
        snaps.head.models.size shouldBe TestSigil.cache.all.size
      }
    }

    "narrow by provider (case-insensitive)" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.handleNotice(RequestModelCatalog(provider = Some("TEST")), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        val snaps = collect[ModelCatalogSnapshot](recorded)
        snaps should not be empty
        snaps.head.models.forall(_.provider.equalsIgnoreCase("test")) shouldBe true
      }
    }

    "narrow by case-insensitive substring `query`" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.handleNotice(RequestModelCatalog(query = Some("vision")), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        val snaps = collect[ModelCatalogSnapshot](recorded)
        snaps should not be empty
        // TestSigil seeds a model with id "test/vision-model".
        snaps.head.models.exists(_._id.value.contains("vision")) shouldBe true
      }
    }
  }

  "ParticipantProjectionUpdated (sigil #291)" should {

    "broadcast when an app writes through setParticipantContext" in {
      val convId = Conversation.id(s"p291-broadcast-${rapid.Unique()}")
      val topic = TopicEntry(TestTopicId, "t", "t")
      val conv = Conversation(_id = convId, topics = List(topic))
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.setParticipantContext(convId, TestUser, ContextKey("activeTarget"), "store-A")
        _ <- Task.sleep(200.millis)
      } yield {
        val updates = collect[ParticipantProjectionUpdated](recorded)
          .filter(_.conversationId == convId)
        updates should not be empty
        updates.last.projection.extraContext.get(ContextKey("activeTarget")) shouldBe Some("store-A")
      }
    }

    "suppress broadcast on framework-internal cache writes (broadcast = false)" in {
      val convId = Conversation.id(s"p291-quiet-${rapid.Unique()}")
      val topic = TopicEntry(TestTopicId, "t", "t")
      val conv = Conversation(_id = convId, topics = List(topic))
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- Task.sleep(100.millis)
        // setProviderResponseState passes broadcast = false internally.
        _ <- TestSigil.setProviderResponseState(convId, TestUser, "resp-123", 42)
        _ <- Task.sleep(200.millis)
      } yield {
        val updates = collect[ParticipantProjectionUpdated](recorded)
          .filter(_.conversationId == convId)
        updates shouldBe Nil
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
