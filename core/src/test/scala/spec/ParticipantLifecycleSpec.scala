package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.participant.{DefaultAgentParticipant, Participant, ParticipantId}
import sigil.provider.{GenerationSettings, Instructions}
import sigil.signal.{ParticipantAdded, ParticipantRemoved, ParticipantUpdated, Signal}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Coverage for [[Sigil.addParticipant]] / `removeParticipant` /
 * `updateParticipant` + the three lifecycle [[Notice]] subtypes:
 *
 *   - Each lifecycle method publishes the matching Notice on success.
 *   - Notices carry the right keys (`conversationId`, full
 *     `Participant` record on add/update, `ParticipantId` on remove).
 *   - Idempotent paths (re-add, remove-of-absent, update-of-absent)
 *     emit nothing — no spurious wire traffic.
 *   - `Participant.displayName` / `avatarUrl` round-trip through the
 *     polymorphic Notice payload so consumers receive display info
 *     directly without an extra lookup.
 */
class ParticipantLifecycleSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Test-only participant subtype carrying display fields. Mirrors the
  // shape downstream apps will use (Voidcraft / Sage user records).
  case class DisplayUser(override val id: ParticipantId,
                         override val displayName: String,
                         override val avatarUrl: Option[String] = None) extends Participant derives RW
  Participant.register(summon[RW[DisplayUser]])

  private def freshConvId(suffix: String): Id[Conversation] =
    Conversation.id(s"participant-spec-$suffix-${rapid.Unique()}")

  private def subscribe(): (ConcurrentLinkedQueue[Signal], () => Unit) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    @volatile var running = true
    TestSigil.signals
      .takeWhile(_ => running)
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    (recorded, () => running = false)
  }

  "Sigil.addParticipant" should {
    "publish ParticipantAdded carrying the full Participant record (display info included)" in {
      val convId = freshConvId("add")
      val (recorded, stop) = subscribe()
      val p = DisplayUser(TestUser, displayName = "Test User", avatarUrl = Some("https://example.invalid/avatar.png"))
      val seed = Conversation(topics = TestTopicStack, _id = convId)
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(seed)))
        _ <- TestSigil.addParticipant(convId, p)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        import scala.jdk.CollectionConverters.*
        val notices = recorded.iterator().asScala.collect { case n: ParticipantAdded if n.conversationId == convId => n }.toList
        notices should have size 1
        notices.head.participant.id shouldBe TestUser
        notices.head.participant.displayName shouldBe "Test User"
        notices.head.participant.avatarUrl shouldBe Some("https://example.invalid/avatar.png")
      }
    }

    "be silent on a re-add (participant already present)" in {
      val convId = freshConvId("readd")
      val p = DisplayUser(TestUser, displayName = "Already There")
      val seed = Conversation(topics = TestTopicStack, participants = List(p), _id = convId)
      val (recorded, stop) = subscribe()
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(seed)))
        _ <- TestSigil.addParticipant(convId, p)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        import scala.jdk.CollectionConverters.*
        val notices = recorded.iterator().asScala.collect { case n: ParticipantAdded if n.conversationId == convId => n }.toList
        notices shouldBe empty
      }
    }
  }

  "Sigil.removeParticipant" should {
    "publish ParticipantRemoved with the participant id" in {
      val convId = freshConvId("remove")
      val p = DisplayUser(TestUser, displayName = "Leaving")
      val seed = Conversation(topics = TestTopicStack, participants = List(p), _id = convId)
      val (recorded, stop) = subscribe()
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(seed)))
        _ <- TestSigil.removeParticipant(convId, TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        import scala.jdk.CollectionConverters.*
        val notices = recorded.iterator().asScala.collect { case n: ParticipantRemoved if n.conversationId == convId => n }.toList
        notices should have size 1
        notices.head.participantId shouldBe TestUser
      }
    }

    "be silent on a remove-of-absent (idempotent)" in {
      val convId = freshConvId("remove-absent")
      val seed = Conversation(topics = TestTopicStack, _id = convId)
      val (recorded, stop) = subscribe()
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(seed)))
        _ <- TestSigil.removeParticipant(convId, TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        import scala.jdk.CollectionConverters.*
        val notices = recorded.iterator().asScala.collect { case n: ParticipantRemoved if n.conversationId == convId => n }.toList
        notices shouldBe empty
      }
    }
  }

  "Sigil.updateParticipant" should {
    "replace the conversation's participant record and broadcast ParticipantUpdated" in {
      val convId = freshConvId("update")
      val before = DisplayUser(TestUser, displayName = "Old Name", avatarUrl = None)
      val after  = DisplayUser(TestUser, displayName = "New Name", avatarUrl = Some("https://example.invalid/v2.png"))
      val seed = Conversation(topics = TestTopicStack, participants = List(before), _id = convId)
      val (recorded, stop) = subscribe()
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(seed)))
        updated <- TestSigil.updateParticipant(convId, after)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        // DB record reflects the new display info.
        updated.participants.size shouldBe 1
        updated.participants.head.displayName shouldBe "New Name"
        updated.participants.head.avatarUrl shouldBe Some("https://example.invalid/v2.png")
        // The Notice carried the new record.
        import scala.jdk.CollectionConverters.*
        val notices = recorded.iterator().asScala.collect { case n: ParticipantUpdated if n.conversationId == convId => n }.toList
        notices should have size 1
        notices.head.participant.displayName shouldBe "New Name"
      }
    }

    "be silent when the participant isn't currently in the conversation" in {
      val convId = freshConvId("update-absent")
      val seed = Conversation(topics = TestTopicStack, _id = convId)
      val ghost = DisplayUser(TestUser, displayName = "Not Here")
      val (recorded, stop) = subscribe()
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(seed)))
        _ <- TestSigil.updateParticipant(convId, ghost)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        import scala.jdk.CollectionConverters.*
        val notices = recorded.iterator().asScala.collect { case n: ParticipantUpdated if n.conversationId == convId => n }.toList
        notices shouldBe empty
      }
    }
  }

  "Participant default display info" should {
    "default displayName to id.value and avatarUrl to None for headless agents" in Task {
      val agent = DefaultAgentParticipant(
        id                 = TestAgent,
        modelId            = Model.id("test", "model"),
        instructions       = Instructions(),
        generationSettings = GenerationSettings()
      )
      agent.displayName shouldBe TestAgent.value
      agent.avatarUrl shouldBe None
      succeed
    }
  }

  "Bug #47 — DefaultAgentParticipant Definition" should {
    "surface displayName and avatarUrl as RW fields so cross-language codegen can emit them" in Task {
      import fabric.define.DefType
      val defn = summon[RW[DefaultAgentParticipant]].definition
      val obj  = defn.defType match {
        case o: DefType.Obj => o
        case other          => fail(s"Expected DefType.Obj; saw $other")
      }
      // Fabric's `@serialized override def` on the subtype is what
      // promotes these to fields on the case-class Definition.
      // Without it, codegen consumers (spice's Dart generator) can't
      // emit `.displayName` / `.avatarUrl` accessors on the abstract
      // parent — bug #47.
      obj.map.keys.toSet should contain allOf ("displayName", "avatarUrl")
      succeed
    }

    "expose displayName and avatarUrl in Participant.commonFields once two subtypes share them" in Task {
      // DisplayUser is registered above; DefaultAgentParticipant is registered by Sigil at startup.
      // Both subtypes carry displayName and avatarUrl, so the poly's commonFields intersection
      // should include them — that's what spice's `generateDartPoly` reads to emit the abstract
      // parent's getters in Dart.
      import fabric.define.DefType
      val polyDef = summon[RW[Participant]].definition
      polyDef.defType match {
        case p: DefType.Poly =>
          p.commonFields.keys.toSet should contain allOf ("displayName", "avatarUrl")
        case other => fail(s"Expected DefType.Poly; saw $other")
      }
      succeed
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
