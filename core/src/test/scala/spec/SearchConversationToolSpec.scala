package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.event.Message
import sigil.signal.EventState
import sigil.tool.model.{ResponseContent, SearchConversationInput}
import sigil.tool.util.SearchConversationTool

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
    val view = ConversationView(conversationId = convIdArg, _id = ConversationView.idFor(convIdArg))
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conv,
      conversationView = view,
      turnInput = TurnInput(view)
    )
  }

  "SearchConversationTool (fallback path)" should {
    "return a Message containing matches scoped to the caller's conversation" in {
      text("We deployed Qdrant to the staging cluster this morning.")
      text("Lunch choices: sushi, tacos, pizza.")
      text("Qdrant indexing for the documents will start tonight.")
      text("This is noise from an UNRELATED conversation about Qdrant.", conv = otherConvId.value)

      val stream = SearchConversationTool.execute(
        SearchConversationInput(query = "Qdrant"),
        contextFor(convId)
      )
      stream.toList.map { emitted =>
        val message = emitted.collectFirst { case m: Message => m }.getOrElse(fail("expected a Message"))
        val body = message.content.collect { case ResponseContent.Text(t) => t }.mkString
        body should include("deployed Qdrant")
        body should include("Qdrant indexing")
        body should not include "UNRELATED"
      }
    }

    "emit an empty-result Message when nothing matches" in {
      val stream = SearchConversationTool.execute(
        SearchConversationInput(query = "zzznomatch"),
        contextFor(convId)
      )
      stream.toList.map { emitted =>
        val body = emitted.collectFirst { case m: Message => m }
          .map(_.content.collect { case ResponseContent.Text(t) => t }.mkString)
          .getOrElse("")
        body should startWith("No results")
      }
    }
  }
}
