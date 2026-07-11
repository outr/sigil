package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.event.{Event, Message, ToolOutcome}
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.core.RespondTool
import sigil.tool.model.{ResponseContent, RespondInput, SearchConversationInput}
import sigil.tool.util.SearchConversationTool

/**
 * Regression for sigil #289 — the conversation-query tools
 * (`search_conversation`, `reload_content`) accept an optional
 * `conversationId` and gate cross-conversation reads via
 * [[Sigil.canReadConversation]]. Allowed targets: own conversation,
 * parent, or a worker (direct child).
 */
class CrossConversationReadSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def turnContextFor(conv: Conversation): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conv,
      turnInput = TurnInput(conversationId = conv._id),
      model = TestSigil.defaultTestModel
    )

  /**
   * Seed a small conversation pair: parent + one worker, plus an
   * unrelated conversation for the negative access test.
   */
  private def seedConversations(label: String): Task[(Conversation, Conversation, Conversation)] = {
    val parentId = Conversation.id(s"$label-parent-${rapid.Unique()}")
    val workerId = Conversation.id(s"$label-worker-${rapid.Unique()}")
    val strangerId = Conversation.id(s"$label-stranger-${rapid.Unique()}")
    val topic = TopicEntry(sigil.conversation.Topic.id(s"topic-$label"), "test", "test")
    val parent = Conversation(_id = parentId, topics = List(topic))
    val worker = Conversation(_id = workerId, topics = List(topic), parentConversationId = Some(parentId))
    val stranger = Conversation(_id = strangerId, topics = List(topic))
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(parent)))
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(worker)))
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(stranger)))
    } yield (parent, worker, stranger)
  }

  "Sigil.canReadConversation" should {

    "allow current → current" in {
      for {
        (parent, _, _) <- seedConversations("self")
        result <- TestSigil.canReadConversation(parent._id, parent._id)
      } yield result.isRight shouldBe true
    }

    "allow parent → worker (child)" in {
      for {
        (parent, worker, _) <- seedConversations("p2w")
        result <- TestSigil.canReadConversation(parent._id, worker._id)
      } yield result.isRight shouldBe true
    }

    "allow worker → parent" in {
      for {
        (parent, worker, _) <- seedConversations("w2p")
        result <- TestSigil.canReadConversation(worker._id, parent._id)
      } yield result.isRight shouldBe true
    }

    "REFUSE unrelated → stranger" in {
      for {
        (parent, _, stranger) <- seedConversations("refuse")
        result <- TestSigil.canReadConversation(parent._id, stranger._id)
      } yield {
        result.isLeft shouldBe true
        result.left.toOption.get should include("not the current")
      }
    }

    "REFUSE sibling worker → sibling worker (no transitive grandparent traversal)" in {
      // worker1 and worker2 share a parent but cannot read each other.
      val parentId = Conversation.id(s"siblings-parent-${rapid.Unique()}")
      val w1Id = Conversation.id(s"siblings-w1-${rapid.Unique()}")
      val w2Id = Conversation.id(s"siblings-w2-${rapid.Unique()}")
      val topic = TopicEntry(sigil.conversation.Topic.id(s"topic-siblings"), "test", "test")
      val parent = Conversation(_id = parentId, topics = List(topic))
      val w1 = Conversation(_id = w1Id, topics = List(topic), parentConversationId = Some(parentId))
      val w2 = Conversation(_id = w2Id, topics = List(topic), parentConversationId = Some(parentId))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(parent)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(w1)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(w2)))
        result <- TestSigil.canReadConversation(w1._id, w2._id)
      } yield result.isLeft shouldBe true
    }
  }

  "SearchConversationTool with conversationId" should {

    "search a worker conversation from its parent" in {
      for {
        (parent, worker, _) <- seedConversations("search-cross")
        // Seed a message in the worker conversation.
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = worker._id,
          topicId = worker.topics.head.id,
          content = Vector(ResponseContent.Text("unique-search-marker-zzqq")),
          state = EventState.Complete
        ))
        _ <- Task.sleep(scala.concurrent.duration.Duration(100, "millis"))
        // Run search_conversation from the parent, targeting the worker.
        signals <- SearchConversationTool.execute(
          SearchConversationInput(
            query = "unique-search-marker-zzqq",
            conversationId = Some(worker._id)),
          turnContextFor(parent),
          Event.id()
        ).toList
      } yield {
        val success = signals.collectFirst {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) => d
        }
        success.flatMap(_.output) match {
          case Some(out: sigil.tool.model.SearchConversationOutput) =>
            out.hits.exists(_.snippet.contains("unique-search-marker-zzqq")) shouldBe true
          case other =>
            fail(s"expected SearchConversationOutput, got $other")
        }
      }
    }

    "REFUSE searching a stranger conversation with a didactic Failure" in {
      for {
        (parent, _, stranger) <- seedConversations("search-refuse")
        signals <- SearchConversationTool.execute(
          SearchConversationInput(
            query = "anything",
            conversationId = Some(stranger._id)),
          turnContextFor(parent),
          Event.id()
        ).toList
      } yield {
        val failures = signals.collect {
          case d: ToolDelta => d.outcome
        }.flatten.collect { case f: ToolOutcome.Failure => f }
        failures should not be empty
        failures.head.reason should include("cannot read conversation")
      }
    }

    "walk mode (empty query) returns chronological events from target conversation" in {
      for {
        (parent, worker, _) <- seedConversations("walk-cross")
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = worker._id,
          topicId = worker.topics.head.id,
          content = Vector(ResponseContent.Text("walk-event-1")),
          state = EventState.Complete
        ))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = worker._id,
          topicId = worker.topics.head.id,
          content = Vector(ResponseContent.Text("walk-event-2")),
          state = EventState.Complete
        ))
        _ <- Task.sleep(scala.concurrent.duration.Duration(100, "millis"))
        signals <- SearchConversationTool.execute(
          SearchConversationInput(query = "", conversationId = Some(worker._id), limit = 10),
          turnContextFor(parent),
          Event.id()
        ).toList
      } yield {
        val success = signals.collectFirst {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) => d
        }
        success.flatMap(_.output) match {
          case Some(out: sigil.tool.model.SearchConversationOutput) =>
            val texts = out.hits.map(_.snippet)
            texts should contain("walk-event-1")
            texts should contain("walk-event-2")
            // Chronological: event-1 before event-2.
            texts.indexOf("walk-event-1") should be < texts.indexOf("walk-event-2")
          case other =>
            fail(s"expected SearchConversationOutput, got $other")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
