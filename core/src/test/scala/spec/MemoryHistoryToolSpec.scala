package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{Conversation, ContextMemory, ConversationView, MemorySource, TurnInput}
import sigil.event.Message
import sigil.tool.memory.{MemoryHistoryInput, MemoryHistoryTool}
import sigil.tool.model.ResponseContent

/**
 * Coverage for [[MemoryHistoryTool]] — surfaces every version of a
 * keyed memory chronologically (oldest → newest, current marked).
 * Seeds via [[sigil.Sigil.upsertMemoryByKey]] directly rather than
 * routing through a write tool.
 */
class MemoryHistoryToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private def convId(suffix: String): Id[Conversation] =
    Conversation.id(s"memhist-$suffix-${rapid.Unique()}")

  private def ctx(c: Id[Conversation]): TurnContext = {
    val view = ConversationView(conversationId = c, _id = ConversationView.idFor(c))
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = Conversation(topics = TestTopicStack, _id = c),
      conversationView = view,
      turnInput = TurnInput(view)
    )
  }

  private def bodyOf(m: Message): String =
    m.content.collect { case ResponseContent.Text(t) => t }.mkString

  private def memoryAt(key: String, fact: String): ContextMemory =
    ContextMemory(
      fact     = fact,
      label    = "Language",
      summary  = fact,
      source   = MemorySource.Explicit,
      spaceId  = TestSpace,
      key      = Some(key)
    )

  "MemoryHistoryTool" should {
    "render every version chronologically with current + archived markers" in {
      val c = convId("hist")
      val key = "pref.lang.history"
      for {
        _ <- TestSigil.upsertMemoryByKey(memoryAt(key, "Scala"))
        _ <- TestSigil.upsertMemoryByKey(memoryAt(key, "Rust"))
        events <- MemoryHistoryTool.execute(MemoryHistoryInput(
          key = key, spaceId = Some(TestSpace)), ctx(c)).toList
      } yield {
        val body = bodyOf(events.head.asInstanceOf[Message])
        body should include("2 version(s)")
        body should include("Scala")
        body should include("Rust")
        body should include("(current)")
        body should include("(archived)")
      }
    }
  }
}
