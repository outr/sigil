package spec

import fabric.io.JsonParser
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{Conversation, ContextMemory, ConversationView, MemorySource, TopicEntry, TurnInput}
import sigil.event.{Event, Message}
import sigil.tool.context.{ListMemoriesInput, ListMemoriesTool}
import sigil.tool.model.ResponseContent

/**
 * Coverage for [[ListMemoriesTool]] — the general "what do you
 * remember" surface. Filters (space / pinned / query) compose; pagination
 * via offset + limit; the agent reads the JSON output on its next turn.
 */
class ListMemoriesSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private def makeContext(convId: Id[Conversation]): TurnContext = {
    val topic = TopicEntry(
      id = sigil.conversation.Topic.id(s"topic-$convId"),
      label = "test",
      summary = "test"
    )
    val conv = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conv,
      conversationView = ConversationView(
        conversationId = convId,
        _id = ConversationView.idFor(convId)
      ),
      turnInput = TurnInput(ConversationView(
        conversationId = convId,
        _id = ConversationView.idFor(convId)
      ))
    )
  }

  private def reseed(accessible: Set[SpaceId] = Set(GlobalSpace, TestSpace)): Unit = {
    TestSigil.reset()
    TestSigil.setAccessibleSpaces(_ => Task.pure(accessible))
    TestSigil.withDB(_.memories.transaction { tx =>
      tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
    }).sync()
  }

  private def seed(key: String,
                   fact: String,
                   in: SpaceId = GlobalSpace,
                   pinned: Boolean = false,
                   label: String = ""): Task[ContextMemory] =
    TestSigil.persistMemory(ContextMemory(
      fact = fact,
      key = key,
      label = label,
      source = MemorySource.Explicit,
      spaceId = in,
      pinned = pinned
    ))

  private def runTool(input: ListMemoriesInput, ctx: TurnContext): Task[fabric.Json] =
    ListMemoriesTool.execute(input, ctx).toList.map { events =>
      val msg = events.collectFirst { case m: Message => m }.get
      val text = msg.content.collectFirst { case t: ResponseContent.Text => t.text }.getOrElse("")
      JsonParser(text)
    }

  "ListMemoriesTool" should {
    "return every accessible memory by default" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-all-${rapid.Unique()}"))
      for {
        _      <- seed("k.a", "Alice prefers blue.")
        _      <- seed("k.b", "Bob prefers green.", pinned = true)
        _      <- seed("k.c", "Carol prefers red.")
        result <- runTool(ListMemoriesInput(), ctx)
      } yield {
        val memories = result("memories").asVector
        memories.size shouldBe 3
        val keys = memories.map(_("key").asString).toSet
        keys shouldBe Set("k.a", "k.b", "k.c")
        // Pinned should sort first.
        memories.head("pinned").asBoolean shouldBe true
        memories.head("key").asString shouldBe "k.b"
      }
    }

    "filter by pinned status" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-pinned-${rapid.Unique()}"))
      for {
        _      <- seed("k.unpinned-1", "First.")
        _      <- seed("k.pinned-1",   "Second.", pinned = true)
        _      <- seed("k.unpinned-2", "Third.")
        pinned <- runTool(ListMemoriesInput(pinned = Some(true)), ctx)
        unpinned <- runTool(ListMemoriesInput(pinned = Some(false)), ctx)
      } yield {
        pinned("memories").asVector.map(_("key").asString) shouldBe Vector("k.pinned-1")
        unpinned("memories").asVector.map(_("key").asString).toSet shouldBe Set("k.unpinned-1", "k.unpinned-2")
      }
    }

    "filter by space" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-space-${rapid.Unique()}"))
      for {
        _      <- seed("k.global-1", "Global one.")
        _      <- seed("k.test-1",   "Test one.", in = TestSpace)
        _      <- seed("k.test-2",   "Test two.", in = TestSpace)
        result <- runTool(ListMemoriesInput(spaces = Set(TestSpace)), ctx)
      } yield {
        val keys = result("memories").asVector.map(_("key").asString).toSet
        keys shouldBe Set("k.test-1", "k.test-2")
      }
    }

    "filter by case-insensitive substring query" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-query-${rapid.Unique()}"))
      for {
        _      <- seed("k.scala", "User likes Scala for backend services.")
        _      <- seed("k.python", "User uses Python for data work.")
        _      <- seed("k.colors", "User's favourite colour is blue.", label = "Blue is best")
        result <- runTool(ListMemoriesInput(query = Some("SCALA")), ctx)
      } yield {
        val keys = result("memories").asVector.map(_("key").asString).toSet
        keys shouldBe Set("k.scala")
      }
    }

    "paginate via offset + limit" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-page-${rapid.Unique()}"))
      val seedAll = (1 to 7).map(i => seed(s"k.$i", s"Memory $i."))
      for {
        _     <- Task.sequence(seedAll.toList).unit
        pg1   <- runTool(ListMemoriesInput(offset = 0, limit = 3), ctx)
        pg2   <- runTool(ListMemoriesInput(offset = 3, limit = 3), ctx)
        pg3   <- runTool(ListMemoriesInput(offset = 6, limit = 3), ctx)
      } yield {
        pg1("memories").asVector.size shouldBe 3
        pg2("memories").asVector.size shouldBe 3
        pg3("memories").asVector.size shouldBe 1
        pg1("page")("totalMatched").asInt shouldBe 7
        pg1("page")("hasMore").asBoolean shouldBe true
        pg3("page")("hasMore").asBoolean shouldBe false
        // No overlap across pages
        val all = (pg1("memories").asVector ++ pg2("memories").asVector ++ pg3("memories").asVector)
          .map(_("key").asString).toSet
        all shouldBe (1 to 7).map(i => s"k.$i").toSet
      }
    }

    "clamp limit to MaxPageSize" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-clamp-${rapid.Unique()}"))
      for {
        _      <- seed("k.only", "Only memory.")
        result <- runTool(ListMemoriesInput(limit = 9999), ctx)
      } yield {
        // Server clamps the page-info `limit` to the max (100) but the
        // returned array reflects the actual count.
        result("page")("limit").asInt should be <= 100
        result("memories").asVector.size shouldBe 1
      }
    }

    "return an empty list with a note when no spaces are accessible" in {
      reseed(accessible = Set.empty)
      val ctx = makeContext(Conversation.id(s"list-noaccess-${rapid.Unique()}"))
      runTool(ListMemoriesInput(), ctx).map { result =>
        result("memories").asVector shouldBe empty
        result("note").asString should include("No accessible memory spaces")
      }
    }

    "compose pinned + query filters" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-compose-${rapid.Unique()}"))
      for {
        _      <- seed("k.pinned-scala",   "Always use Scala for backend.", pinned = true)
        _      <- seed("k.unpinned-scala", "Soft preference: Scala for scripts.")
        _      <- seed("k.pinned-python",  "Always use Python for data.", pinned = true)
        result <- runTool(ListMemoriesInput(pinned = Some(true), query = Some("scala")), ctx)
      } yield {
        result("memories").asVector.map(_("key").asString) shouldBe Vector("k.pinned-scala")
      }
    }
  }
}
