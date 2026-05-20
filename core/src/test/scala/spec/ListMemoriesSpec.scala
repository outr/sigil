package spec

import fabric.rw.RW
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{ConversationView, Conversation, ContextMemory, MemorySource, TopicEntry, TurnInput}
import sigil.event.ToolResults
import sigil.tool.context.{ListMemoriesInput, ListMemoriesTool}
import sigil.tool.model.{ListMemoriesOutput, MemoryListEntry, MemoryListPage}

/**
 * Coverage for [[ListMemoriesTool]] — the general "what do you
 * remember" surface. Filters (space / pinned / query) compose;
 * pagination via offset + limit; the tool emits a typed
 * [[ListMemoriesOutput]] the agent reads on its next turn.
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
      turnInput = TurnInput(ConversationView(
        conversationId = convId
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
                   label: String = "test memory"): Task[ContextMemory] =
    TestSigil.persistMemory(ContextMemory(
      fact = fact,
      label = label,
      summary = fact,
      key = Some(key),
      source = MemorySource.Explicit,
      spaceId = in,
      pinned = pinned
    ))

  /** Decode the `ToolResults.typed` payload back to [[ListMemoriesOutput]]. */
  private def runTool(input: ListMemoriesInput, ctx: TurnContext): Task[ListMemoriesOutput] =
    ListMemoriesTool.execute(input, ctx).toList.map { events =>
      val json = events.collectFirst { case t: ToolResults if t.typed.isDefined => t.typed.get }
        .getOrElse(fail(s"expected a ToolResults with a typed payload; saw: ${events.map(_.getClass.getSimpleName).mkString(", ")}"))
      summon[RW[ListMemoriesOutput]].write(json)
    }

  /** Unwrap a `Listed` result or fail the test. */
  private def listed(out: ListMemoriesOutput): (List[MemoryListEntry], MemoryListPage) = out match {
    case ListMemoriesOutput.Listed(memories, page) => (memories, page)
    case other                                     => fail(s"expected Listed, got $other")
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
        val (memories, _) = listed(result)
        memories.size shouldBe 3
        memories.map(_.key).toSet shouldBe Set("k.a", "k.b", "k.c")
        // Pinned should sort first.
        memories.head.pinned shouldBe true
        memories.head.key shouldBe "k.b"
      }
    }

    "filter by pinned status" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-pinned-${rapid.Unique()}"))
      for {
        _        <- seed("k.unpinned-1", "First.")
        _        <- seed("k.pinned-1",   "Second.", pinned = true)
        _        <- seed("k.unpinned-2", "Third.")
        pinned   <- runTool(ListMemoriesInput(pinned = Some(true)), ctx)
        unpinned <- runTool(ListMemoriesInput(pinned = Some(false)), ctx)
      } yield {
        listed(pinned)._1.map(_.key) shouldBe List("k.pinned-1")
        listed(unpinned)._1.map(_.key).toSet shouldBe Set("k.unpinned-1", "k.unpinned-2")
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
      } yield listed(result)._1.map(_.key).toSet shouldBe Set("k.test-1", "k.test-2")
    }

    "filter by case-insensitive substring query" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-query-${rapid.Unique()}"))
      for {
        _      <- seed("k.scala", "User likes Scala for backend services.")
        _      <- seed("k.python", "User uses Python for data work.")
        _      <- seed("k.colors", "User's favourite colour is blue.", label = "Blue is best")
        result <- runTool(ListMemoriesInput(query = Some("SCALA")), ctx)
      } yield listed(result)._1.map(_.key).toSet shouldBe Set("k.scala")
    }

    "paginate via offset + limit" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-page-${rapid.Unique()}"))
      val seedAll = (1 to 7).map(i => seed(s"k.$i", s"Memory $i."))
      for {
        _   <- Task.sequence(seedAll.toList).unit
        pg1 <- runTool(ListMemoriesInput(offset = 0, limit = 3), ctx)
        pg2 <- runTool(ListMemoriesInput(offset = 3, limit = 3), ctx)
        pg3 <- runTool(ListMemoriesInput(offset = 6, limit = 3), ctx)
      } yield {
        val (m1, p1) = listed(pg1)
        val (m2, _)  = listed(pg2)
        val (m3, p3) = listed(pg3)
        m1.size shouldBe 3
        m2.size shouldBe 3
        m3.size shouldBe 1
        p1.totalMatched shouldBe 7
        p1.hasMore shouldBe true
        p3.hasMore shouldBe false
        // No overlap across pages
        (m1 ++ m2 ++ m3).map(_.key).toSet shouldBe (1 to 7).map(i => s"k.$i").toSet
      }
    }

    "clamp limit to MaxPageSize" in {
      reseed()
      val ctx = makeContext(Conversation.id(s"list-clamp-${rapid.Unique()}"))
      for {
        _      <- seed("k.only", "Only memory.")
        result <- runTool(ListMemoriesInput(limit = 9999), ctx)
      } yield {
        val (memories, page) = listed(result)
        // Server clamps the page-info `limit` to the max (100) but the
        // returned list reflects the actual count.
        page.limit should be <= 100
        memories.size shouldBe 1
      }
    }

    "return NoAccessibleSpaces with a note when no spaces are accessible" in {
      reseed(accessible = Set.empty)
      val ctx = makeContext(Conversation.id(s"list-noaccess-${rapid.Unique()}"))
      runTool(ListMemoriesInput(), ctx).map {
        case ListMemoriesOutput.NoAccessibleSpaces(note) =>
          note should include("No accessible memory spaces")
        case other => fail(s"expected NoAccessibleSpaces, got $other")
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
      } yield listed(result)._1.map(_.key) shouldBe List("k.pinned-scala")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
