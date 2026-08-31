package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TurnInput}
import fabric.rw.*
import sigil.event.Message
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.model.{ResponseContent, SearchConversationInput, SearchConversationOutput}
import sigil.tool.util.SearchConversationTool
import sigil.event.Event

/**
 * Mechanical coverage of [[SearchConversationTool]] using TestSigil's
 * fallback (Lucene / substring) search path. When vector search isn't
 * wired, the tool routes through [[sigil.Sigil.searchConversationEvents]]'s
 * substring branch; this spec seeds events and asserts the right ones
 * surface in the emitted Message.
 */
class SearchConversationToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id(s"search-conv-${rapid.Unique()}")
  private val otherConvId = Conversation.id(s"other-conv-${rapid.Unique()}")

  private def persist(m: Message): Message = {
    TestSigil.withDB(_.events.transaction(_.upsert(m))).sync()
    m
  }

  private def text(content: String, conv: String = convId.value): Message = persist(Message(
    participantId = TestUser,
    conversationId = if (conv == convId.value) convId else otherConvId,
    topicId = TestTopicId,
    content = Vector(ResponseContent.Text(content)),
    state = EventState.Complete
  ))

  private def contextFor(convIdArg: lightdb.id.Id[Conversation]): TurnContext = {
    val conv = Conversation(_id = convIdArg, topics = List(TestTopicEntry))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()
    val viewConvId = convIdArg
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conv,
      turnInput = TurnInput(conversationId = viewConvId),
      model = TestSigil.defaultTestModel
    )
  }

  private def typed(signals: List[Signal]): SearchConversationOutput =
    signals.collectFirst {
      case d: ToolDelta if d.output.exists(_.isInstanceOf[SearchConversationOutput]) =>
        d.output.get.asInstanceOf[SearchConversationOutput]
    }.getOrElse(fail("expected a settling ToolDelta carrying a SearchConversationOutput"))

  "SearchConversationTool (fallback path)" should {
    "return typed hits scoped to the caller's conversation" in {
      text("We deployed Qdrant to the staging cluster this morning.")
      text("Lunch choices: sushi, tacos, pizza.")
      text("Qdrant indexing for the documents will start tonight.")
      text("This is noise from an UNRELATED conversation about Qdrant.", conv = otherConvId.value)

      val stream = SearchConversationTool.execute(
        SearchConversationInput(query = "Qdrant"),
        contextFor(convId),
        Event.id()
      )
      stream.toList.map { emitted =>
        val out = typed(emitted)
        val combined = out.hits.map(_.snippet).mkString(" | ")
        combined should include("deployed Qdrant")
        combined should include("Qdrant indexing")
        combined should not include "UNRELATED"
      }
    }

    "emit an empty-hits result when nothing matches" in {
      val stream = SearchConversationTool.execute(
        SearchConversationInput(query = "zzznomatch"),
        contextFor(convId),
        Event.id()
      )
      stream.toList.map { emitted =>
        val out = typed(emitted)
        out.count shouldBe 0
        out.hits shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
