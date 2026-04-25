package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, MemoryType, TurnInput}
import sigil.event.Message
import sigil.tool.memory.{ForgetInput, ForgetTool, MemoryHistoryInput, MemoryHistoryTool, RecallInput, RecallTool, RememberInput, RememberTool}
import sigil.tool.model.ResponseContent
import sigil.vector.InMemoryVectorIndex

/**
 * End-to-end coverage for the four agent-facing memory tools —
 * Remember, Recall, Forget, MemoryHistory — exercised against
 * TestSigil + the in-memory vector index. Covers the three
 * RememberTool branches (stored / refreshed / versioned) and the
 * Recall history filter.
 */
class RememberRecallForgetToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  // Wire the deterministic embedder + in-memory vector index so
  // RecallTool's semantic search path is actually exercised (and so
  // unrelated test memories in the same space don't crowd out the
  // target under a fixed limit).
  TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
  TestSigil.setVectorIndex(new InMemoryVectorIndex)
  TestSigil.vectorIndex.ensureCollection(TestSigil.embeddingProvider.dimensions).sync()

  private def convId(suffix: String): Id[Conversation] =
    Conversation.id(s"memtools-$suffix-${rapid.Unique()}")

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

  "RememberTool" should {
    "emit a stored message on first write" in {
      val c = convId("store")
      val input = RememberInput(
        key = "pref.lang.t1",
        label = "Language",
        summary = "User likes Scala",
        content = "Scala",
        memoryType = MemoryType.Preference,
        spaceId = Some(TestSpace)
      )
      RememberTool.execute(input, ctx(c)).toList.map { events =>
        events should have size 1
        bodyOf(events.head.asInstanceOf[Message]) should include("[remember] stored pref.lang.t1")
      }
    }

    "emit a refreshed message on unchanged content" in {
      val c = convId("refresh")
      val input = RememberInput(
        key = "pref.lang.t2",
        label = "Language",
        summary = "User likes Scala",
        content = "Scala",
        spaceId = Some(TestSpace)
      )
      for {
        first <- RememberTool.execute(input, ctx(c)).toList
        second <- RememberTool.execute(input.copy(label = "Language (v2)"), ctx(c)).toList
      } yield {
        bodyOf(first.head.asInstanceOf[Message]) should include("stored")
        bodyOf(second.head.asInstanceOf[Message]) should include("refreshed")
      }
    }

    "emit a versioned message on changed content" in {
      val c = convId("version")
      val key = "pref.lang.t3"
      for {
        first <- RememberTool.execute(RememberInput(
          key = key, label = "L", summary = "S", content = "Scala",
          spaceId = Some(TestSpace)), ctx(c)).toList
        second <- RememberTool.execute(RememberInput(
          key = key, label = "L", summary = "S", content = "Rust",
          spaceId = Some(TestSpace)), ctx(c)).toList
      } yield {
        bodyOf(first.head.asInstanceOf[Message]) should include("stored")
        bodyOf(second.head.asInstanceOf[Message]) should include("updated")
      }
    }
  }

  "RecallTool" should {
    "filter out archived versions by default" in {
      val c = convId("recall-filter")
      val key = "pref.lang.recall1"
      for {
        _ <- RememberTool.execute(RememberInput(
          key = key, label = "L", summary = "recall-filter-marker Scala",
          content = "recall-filter-marker Scala preference",
          spaceId = Some(TestSpace)), ctx(c)).toList
        _ <- RememberTool.execute(RememberInput(
          key = key, label = "L", summary = "recall-filter-marker Rust",
          content = "recall-filter-marker Rust preference",
          spaceId = Some(TestSpace)), ctx(c)).toList
        events <- RecallTool.execute(RecallInput(
          query = "recall-filter-marker", includeHistory = false, limit = 20, spaces = Set(TestSpace)), ctx(c)).toList
      } yield {
        val body = bodyOf(events.head.asInstanceOf[Message])
        // Current version (Rust) visible; archived (Scala) filtered out.
        body should include("Rust")
        body should not include "(archived)"
      }
    }

    "surface archived versions when includeHistory = true" in {
      val c = convId("recall-history")
      val key = "pref.lang.recall2"
      for {
        _ <- RememberTool.execute(RememberInput(
          key = key, label = "L", summary = "Scala summary",
          content = "recall-history-marker Scala",
          spaceId = Some(TestSpace)), ctx(c)).toList
        _ <- RememberTool.execute(RememberInput(
          key = key, label = "L", summary = "Rust summary",
          content = "recall-history-marker Rust",
          spaceId = Some(TestSpace)), ctx(c)).toList
        events <- RecallTool.execute(RecallInput(
          query = "recall-history-marker", includeHistory = true, limit = 20, spaces = Set(TestSpace)), ctx(c)).toList
      } yield {
        val body = bodyOf(events.head.asInstanceOf[Message])
        body should include("(archived)")
        body should include("Scala")
        body should include("Rust")
      }
    }
  }

  "ForgetTool" should {
    "hard-delete every version of a key" in {
      val c = convId("forget")
      val key = "pref.lang.forget1"
      for {
        _ <- RememberTool.execute(RememberInput(
          key = key, label = "L", summary = "S", content = "Scala",
          spaceId = Some(TestSpace)), ctx(c)).toList
        _ <- RememberTool.execute(RememberInput(
          key = key, label = "L", summary = "S", content = "Rust",
          spaceId = Some(TestSpace)), ctx(c)).toList
        events <- ForgetTool.execute(ForgetInput(
          key = key, spaceId = Some(TestSpace)), ctx(c)).toList
        remaining <- TestSigil.memoryHistory(key, TestSpace)
      } yield {
        bodyOf(events.head.asInstanceOf[Message]) should include("removed 2 version")
        remaining shouldBe empty
      }
    }
  }

  "MemoryHistoryTool" should {
    "render every version chronologically" in {
      val c = convId("history")
      val key = "pref.lang.history"
      for {
        _ <- RememberTool.execute(RememberInput(
          key = key, label = "L", summary = "S", content = "Scala",
          spaceId = Some(TestSpace)), ctx(c)).toList
        _ <- RememberTool.execute(RememberInput(
          key = key, label = "L", summary = "S", content = "Rust",
          spaceId = Some(TestSpace)), ctx(c)).toList
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
