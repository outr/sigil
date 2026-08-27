package spec

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.filter.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.SpaceId
import sigil.conversation.Conversation
import sigil.event.{Event, Message, Stop}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * The admin / reporting indexes — `Conversation.space`,
 * `Event.participantId`, `Event.eventType` — run as indexed filters on
 * the embedded RocksDB + Lucene backend (which cannot filter an
 * undeclared field at all), and each index term is exactly the text
 * the document RW writes for that key, which is what lets SQL backends
 * adopt the declarations on their existing columns without a data
 * migration.
 */
class AdminQueryIndexSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def newConv(space: SpaceId): Task[Conversation] = {
    val conv = Conversation(topics = TestTopicStack, space = space, _id = Conversation.id(s"aqi-${rapid.Unique()}"))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def message(convId: Id[Conversation], by: ParticipantId): Message =
    Message(
      participantId  = by,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      content        = Vector(ResponseContent.Text(s"from ${by.value}")),
      state          = EventState.Complete
    )

  private def stop(convId: Id[Conversation], by: ParticipantId): Stop =
    Stop(participantId = by, conversationId = convId, topicId = TestTopicEntry.id)

  private def persist(convId: Id[Conversation], events: List[Event]): Task[Unit] =
    TestSigil.withDB(_.eventsTransaction(convId)(tx => Task.sequence(events.map(tx.upsert)).unit))

  "the Conversation.space index" should {
    "filter conversations by space server-side" in {
      for {
        a <- newConv(TestSpace)
        b <- newConv(TestSpace)
        c <- newConv(MemoryTestSpace)
        inTest <- TestSigil.withDB(_.conversations.transaction(
          _.query.filter(_.space === TestSpace).toList))
        inMemory <- TestSigil.withDB(_.conversations.transaction(
          _.query.filter(_.space === MemoryTestSpace).toList))
      } yield {
        inTest.map(_._id) should contain allElementsOf List(a._id, b._id)
        inTest.map(_._id) should not contain c._id
        inMemory.map(_._id) should contain (c._id)
        inMemory.map(_._id) should contain noneOf (a._id, b._id)
      }
    }

    "index the exact text the document RW writes for the column" in {
      val conv = Conversation(topics = TestTopicStack, space = TestSpace)
      val column = Conversation.rw.read(conv).asObj.value("space")
      JsonFormatter.Compact(SpaceId.rw.read(conv.space)) shouldBe JsonFormatter.Compact(column)
    }
  }

  "the Event.participantId and Event.eventType indexes" should {
    "filter events by participant and by event type server-side" in {
      for {
        conv <- newConv(TestSpace)
        convId = conv._id
        _ <- persist(convId, List(
          message(convId, TestUser),
          message(convId, TestUser),
          message(convId, TestAgent),
          stop(convId, TestUser)
        ))
        byUser <- TestSigil.withDB(_.eventsTransaction(convId)(
          _.query.filter(_.participantId === TestUser).toList))
        byAgent <- TestSigil.withDB(_.eventsTransaction(convId)(
          _.query.filter(_.participantId === TestAgent).toList))
        messages <- TestSigil.withDB(_.eventsTransaction(convId)(
          _.query.filter(_.eventType === "Message").toList))
        stops <- TestSigil.withDB(_.eventsTransaction(convId)(
          _.query.filter(_.eventType === "Stop").toList))
        userMessages <- TestSigil.withDB(_.eventsTransaction(convId)(
          _.query.filter(m => (m.participantId === TestUser) && (m.eventType === "Message")).toList))
      } yield {
        byUser.filter(_.conversationId == convId) should have size 3
        byAgent.filter(_.conversationId == convId) should have size 1
        messages.filter(_.conversationId == convId) should have size 3
        stops.filter(_.conversationId == convId) should have size 1
        userMessages.filter(_.conversationId == convId) should have size 2
        userMessages.forall(_.isInstanceOf[Message]) shouldBe true
      }
    }

    "index the exact text the document RW writes for the columns" in {
      val convId = Conversation.id("aqi-pin")
      val events: List[Event] = List(message(convId, TestUser), stop(convId, TestAgent))
      events.foreach { e =>
        val doc = Event.rw.read(e).asObj.value
        JsonFormatter.Compact(ParticipantId.rw.read(e.participantId)) shouldBe JsonFormatter.Compact(doc("participantId"))
        doc("type").asString shouldBe e.getClass.getSimpleName.stripSuffix("$")
      }
      succeed
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
