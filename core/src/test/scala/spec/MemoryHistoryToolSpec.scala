package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, ContextMemory, MemorySource, TurnInput}
import sigil.event.ToolOutcome
import sigil.signal.ToolDelta
import sigil.tool.TextToolOutput
import sigil.tool.memory.{MemoryHistoryInput, MemoryHistoryTool}
import sigil.event.Event

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
    val viewConvId = c
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = Conversation(topics = TestTopicStack, _id = c),
      turnInput = TurnInput(conversationId = viewConvId)
    )
  }

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
        signals <- MemoryHistoryTool.execute(MemoryHistoryInput(
          key = key, spaceId = Some(TestSpace)), ctx(c), Event.id()).toList
      } yield {
        val body = signals.collectFirst {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) =>
            d.output.collect { case TextToolOutput(text) => text }
        }.flatten
          .getOrElse(fail(s"expected a Success ToolDelta carrying TextToolOutput; saw: ${signals.map(_.getClass.getSimpleName).mkString(", ")}"))
        body should include("2 version(s)")
        body should include("Scala")
        body should include("Rust")
        body should include("(current)")
        body should include("(archived)")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
