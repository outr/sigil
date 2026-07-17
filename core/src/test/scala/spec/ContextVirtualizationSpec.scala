package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ContextSummary, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.ToolContext
import sigil.tool.model.ResponseContent
import sigil.tool.context.{ReloadContentInput, ReloadContentTool}

/**
 * Sigil #316 — lossless-by-reference context virtualization. Covers the
 * watermark cap (a budget shed can never permanently erase the current
 * user task) and the reload-by-id keystone (reload_content resolves an event
 * id → its paginated content, and a summary id → the events it covers).
 */
class ContextVirtualizationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "ctx-virt")
  TestSigil.testModel(modelId)

  private def toolCtx(conv: Conversation): ToolContext = {
    val turn = TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conv,
      turnInput = TurnInput(conversationId = conv._id),
      model = TestSigil.testModel(modelId)
    )
    ToolContext(turn, Event.id(), ReloadContentTool.name)
  }

  "advanceClearedAt cap (#316)" should {
    "never advance the watermark to or past the current user task" in {
      val convId = Conversation.id(s"ctxvirt-cap-${rapid.Unique()}")
      val taskTs = 1000L
      val task = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        role = MessageRole.Standard,
        content = Vector(ResponseContent.Text("the task")),
        state = EventState.Complete,
        timestamp = Timestamp(taskTs)
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(topics = TestTopicStack, _id = convId))))
        _ <- TestSigil.withDB(_.events.transaction(_.upsert(task)))
        _ <- TestSigil.advanceClearedAt(convId, Timestamp(5000L)) // far past the task
        conv <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        val cleared = conv.flatMap(_.clearedAt).map(_.value)
        cleared shouldBe defined
        cleared.get should be < taskTs
      }
    }
  }

  "reload_content reload-by-id (#316 keystone)" should {
    "reload a single event's full content by event id" in {
      val convId = Conversation.id(s"ctxvirt-event-${rapid.Unique()}")
      val big = "X" * 9000
      val evId = Event.id()
      val ev = Message(
        _id = evId,
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        role = MessageRole.Standard,
        content = Vector(ResponseContent.Text(big)),
        state = EventState.Complete
      )
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.withDB(_.events.transaction(_.upsert(ev)))
        res <- ReloadContentTool.invoke(ReloadContentInput(referenceId = evId.value), toolCtx(conv))
      } yield res.text.count(_ == 'X') shouldBe 9000
    }

    "browse a summary's covered events by summary id" in {
      val convId = Conversation.id(s"ctxvirt-summary-${rapid.Unique()}")
      val e1 = Event.id()
      val e2 = Event.id()
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val summary = ContextSummary(
        text = "earlier work",
        conversationId = convId,
        tokenEstimate = 10,
        coversEventIds = List(e1, e2))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.persistSummary(summary)
        res <- ReloadContentTool.invoke(ReloadContentInput(referenceId = summary._id.value), toolCtx(conv))
      } yield {
        res.text should include(e1.value)
        res.text should include(e2.value)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
