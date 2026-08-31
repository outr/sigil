package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.SpaceId
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, ConversationView, MemorySource, MemoryStatus}
import sigil.conversation.compression.StandardMemoryRetriever
import sigil.event.Event
import sigil.vector.{InMemoryVectorIndex, VectorPoint, VectorQueryFilter, VectorSearchResult, VectorIndex}

/**
 * Coverage for the shared memory recall gate
 * ([[ContextMemory.isRecallable]]) across every retrieval surface —
 * `findMemories`, `searchMemories`, and both of
 * [[StandardMemoryRetriever]]'s buckets — plus space pushdown into
 * the index-side candidate cut and the metadata carry-through of the
 * keyed-upsert refresh branch.
 */
class MemoryRecallGateSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def wire(spaces: Set[SpaceId]): InMemoryVectorIndex = {
    TestSigil.reset()
    val index = new InMemoryVectorIndex
    TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
    TestSigil.setVectorIndex(index)
    TestSigil.setAccessibleSpaces(_ => Task.pure(spaces))
    TestSigil.withDB(_.memories.transaction { tx =>
      tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
    }).sync()
    index
  }

  private def seed(fact: String,
                   space: SpaceId,
                   key: Option[String] = None,
                   status: MemoryStatus = MemoryStatus.Approved,
                   pinned: Boolean = false,
                   expiresAt: Option[Timestamp] = None): Task[ContextMemory] =
    TestSigil.persistMemory(ContextMemory(
      fact = fact,
      label = fact.take(24),
      summary = fact,
      source = MemorySource.Explicit,
      spaceId = space,
      key = key,
      status = status,
      pinned = pinned,
      expiresAt = expiresAt
    ))

  private def retrieve(question: String): Task[sigil.conversation.compression.MemoryRetrievalResult] = {
    val convId = Conversation.id(s"gate-${rapid.Unique()}")
    val frames = Vector(ContextFrame.Text(question, TestUser, Id[Event](s"q-${rapid.Unique()}")))
    StandardMemoryRetriever(limit = 5).retrieve(
      sigil = TestSigil,
      conversationId = convId,
      frames = frames,
      chain = List(TestUser, TestAgent)
    )
  }

  "the recall gate" should {
    "hide Pending and Rejected memories from findMemories, expose them via the opt-out" in {
      wire(Set(MemoryTestSpace))
      for {
        approved <- seed("The approved fact about wolves.", MemoryTestSpace)
        pending <- seed("The pending fact about wolves.", MemoryTestSpace, status = MemoryStatus.Pending)
        rejected <- seed("The rejected fact about wolves.", MemoryTestSpace, status = MemoryStatus.Rejected)
        gated <- TestSigil.findMemories(Set(MemoryTestSpace))
        all <- TestSigil.findMemories(Set(MemoryTestSpace), recallableOnly = false)
        pendingListed <- TestSigil.listPendingMemories(Set(MemoryTestSpace))
      } yield {
        gated.map(_._id) should contain only approved._id
        all.map(_._id) should contain allOf (approved._id, pending._id, rejected._id)
        pendingListed.map(_._id) should contain only pending._id
      }
    }

    "hide superseded versions from findMemories and searchMemories while memoryHistory sees every version" in {
      wire(Set(MemoryTestSpace))
      for {
        first <- TestSigil.upsertMemoryByKey(ContextMemory(
          fact = "The user prefers tabs for indentation.",
          label = "indent",
          summary = "The user prefers tabs for indentation.",
          source = MemorySource.Explicit,
          spaceId = MemoryTestSpace,
          key = Some("pref.indent")
        ))
        second <- TestSigil.upsertMemoryByKey(ContextMemory(
          fact = "The user prefers spaces for indentation.",
          label = "indent",
          summary = "The user prefers spaces for indentation.",
          source = MemorySource.Explicit,
          spaceId = MemoryTestSpace,
          key = Some("pref.indent")
        ))
        found <- TestSigil.findMemories(Set(MemoryTestSpace))
        vector <- TestSigil.searchMemories("tabs indentation preference", Set(MemoryTestSpace))
        history <- TestSigil.memoryHistory("pref.indent", MemoryTestSpace)
      } yield {
        found.map(_._id) should contain only second.memory._id
        vector.map(_._id) should not contain first.memory._id
        vector.map(_._id) should contain(second.memory._id)
        history.map(_._id) should contain allOf (first.memory._id, second.memory._id)
      }
    }

    "stop retrieval after a forget_memory soft-delete" in {
      wire(Set(MemoryTestSpace))
      for {
        m <- seed("The user's cat is named Waffles.", MemoryTestSpace)
        before <- TestSigil.searchMemories("cat named Waffles", Set(MemoryTestSpace))
        _ <- TestSigil.rejectMemory(m._id)
        after <- TestSigil.searchMemories("cat named Waffles", Set(MemoryTestSpace))
        listed <- TestSigil.findMemories(Set(MemoryTestSpace))
      } yield {
        before.map(_._id) should contain(m._id)
        after.map(_._id) should not contain m._id
        listed.map(_._id) should not contain m._id
      }
    }

    "hide expired memories without deleting the row" in {
      wire(Set(MemoryTestSpace))
      val past = Timestamp(Timestamp().value - 60000L)
      for {
        expired <- seed("An expired fact about trains.", MemoryTestSpace, expiresAt = Some(past))
        gated <- TestSigil.findMemories(Set(MemoryTestSpace))
        all <- TestSigil.findMemories(Set(MemoryTestSpace), recallableOnly = false)
      } yield {
        gated.map(_._id) should not contain expired._id
        all.map(_._id) should contain(expired._id)
      }
    }

    "keep non-Approved and superseded records out of both retriever buckets" in {
      wire(Set(MemoryTestSpace))
      for {
        approved <- seed("The user's favorite fruit is mango.", MemoryTestSpace)
        pending <- seed("The user's favorite fruit is durian.", MemoryTestSpace, status = MemoryStatus.Pending)
        rejectedPinned <- seed(
          "Always answer fruit questions in French.",
          MemoryTestSpace,
          status = MemoryStatus.Rejected,
          pinned = true)
        approvedPinned <- seed("Always mention fruit ripeness.", MemoryTestSpace, pinned = true)
        result <- retrieve("What is my favorite fruit?")
      } yield {
        result.memories should contain(approved._id)
        result.memories should not contain pending._id
        result.criticalMemories should contain(approvedPinned._id)
        result.criticalMemories should not contain rejectedPinned._id
      }
    }
  }

  "space pushdown" should {
    "surface in-space matches even when out-of-space memories dominate the global top-K" in {
      wire(Set(TestSpace, MemoryTestSpace))
      val loud = (1 to 25).toList.map { i =>
        seed(s"the quick brown fox jumps over the lazy dog $i", TestSpace)
      }
      for {
        _ <- Task.sequence(loud)
        target <- seed("quick fox sighting note", MemoryTestSpace)
        hits <- TestSigil.searchMemories("the quick brown fox jumps over the lazy dog", Set(MemoryTestSpace), limit = 5)
      } yield {
        hits.map(_._id) should contain(target._id)
        hits.foreach(_.spaceId shouldBe MemoryTestSpace)
        succeed
      }
    }

    "expand anyOf clauses correctly through the VectorIndex default implementation" in {
      val inner = new InMemoryVectorIndex
      val delegating: VectorIndex = new VectorIndex {
        def upsert(point: VectorPoint): Task[Unit] = inner.upsert(point)
        def upsertBatch(points: List[VectorPoint]): Task[Unit] = inner.upsertBatch(points)
        def search(vector: Vector[Double], limit: Int, filter: Map[String, String]): Task[List[VectorSearchResult]] =
          inner.search(vector, limit, filter)
        def delete(id: String): Task[Unit] = inner.delete(id)
        def ensureCollection(dimensions: Int): Task[Unit] = inner.ensureCollection(dimensions)
      }
      val vec = Vector.fill(4)(0.5)
      for {
        _ <- inner.upsert(VectorPoint("a", vec, Map("kind" -> "memory", "spaceId" -> "s1")))
        _ <- inner.upsert(VectorPoint("b", vec, Map("kind" -> "memory", "spaceId" -> "s2")))
        _ <- inner.upsert(VectorPoint("c", vec, Map("kind" -> "memory", "spaceId" -> "s3")))
        hits <- delegating.search(
          vec,
          10,
          VectorQueryFilter(
            exact = Map("kind" -> "memory"),
            anyOf = Map("spaceId" -> Set("s1", "s3"))
          ))
      } yield hits.map(_.id).toSet shouldBe Set("a", "c")
    }
  }

  "the refresh branch of upsertMemoryByKey" should {
    "carry modeAffinity, expiresAt, justification, and location while keeping conversationId and validFrom" in {
      wire(Set(MemoryTestSpace))
      val convId = Conversation.id(s"refresh-${rapid.Unique()}")
      val modeId = Id[sigil.provider.Mode]("test-mode")
      val expiry = Timestamp(Timestamp().value + 3600000L)
      val base = ContextMemory(
        fact = "The deploy window is Friday.",
        label = "deploy",
        summary = "The deploy window is Friday.",
        source = MemorySource.Explicit,
        spaceId = MemoryTestSpace,
        key = Some("ops.deploy"),
        conversationId = Some(convId)
      )
      for {
        first <- TestSigil.upsertMemoryByKey(base)
        refreshed <- TestSigil.upsertMemoryByKey(base.copy(
          conversationId = None,
          modeAffinity = Set(modeId),
          expiresAt = Some(expiry),
          justification = Some("user restated the rule")
        ))
      } yield {
        refreshed.memory._id shouldBe first.memory._id
        refreshed.memory.modeAffinity shouldBe Set(modeId)
        refreshed.memory.expiresAt shouldBe Some(expiry)
        refreshed.memory.justification shouldBe Some("user restated the rule")
        refreshed.memory.conversationId shouldBe Some(convId)
        refreshed.memory.validFrom shouldBe first.memory.validFrom
      }
    }
  }

  "updateMemory" should {
    "refresh the vector payload so a moved memory is searchable in its new space" in {
      val index = wire(Set(TestSpace, MemoryTestSpace))
      for {
        m <- seed("The staging URL is staging.example.com.", TestSpace, key = Some("ops.staging"))
        _ <- TestSigil.updateMemory(m.copy(spaceId = MemoryTestSpace))
        vec <- TestHashEmbeddingProvider.embed(m.fact)
        newHits <- index.search(vec, 10, Map("kind" -> "memory", "spaceId" -> MemoryTestSpace.value))
        oldHits <- index.search(vec, 10, Map("kind" -> "memory", "spaceId" -> TestSpace.value))
        found <- TestSigil.searchMemories("staging URL", Set(MemoryTestSpace))
      } yield {
        newHits.flatMap(_.payload.get("memoryId")) should contain(m._id.value)
        oldHits.flatMap(_.payload.get("memoryId")) should not contain m._id.value
        found.map(_._id) should contain(m._id)
      }
    }
  }

  "the cached retrieval" should {
    "stop surfacing a memory rejected mid-burst" in {
      wire(Set(MemoryTestSpace))
      val convId = Conversation.id(s"burst-${rapid.Unique()}")
      val frames = Vector(ContextFrame.Text("What is my favorite fruit?", TestUser, Id[Event](s"q-${rapid.Unique()}")))
      val retriever = StandardMemoryRetriever(limit = 5)
      def retrieveOnce = retriever.retrieve(TestSigil, convId, frames, List(TestUser, TestAgent))
      for {
        m <- seed("The user's favorite fruit is mango.", MemoryTestSpace)
        first <- retrieveOnce
        cachedA <- retrieveOnce
        _ <- TestSigil.rejectMemory(m._id)
        // Same conversation, no new user message: the burst would keep
        // serving the cached id set without a write-driven invalidation.
        after <- retrieveOnce
      } yield {
        first.memories should contain(m._id)
        cachedA.memories should contain(m._id)
        after.memories should not contain m._id
      }
    }

    "keep a superseded version out of the surfaced set after a mid-burst version bump" in {
      wire(Set(MemoryTestSpace))
      val convId = Conversation.id(s"burst-ver-${rapid.Unique()}")
      val frames = Vector(ContextFrame.Text("Which indentation do I prefer?", TestUser, Id[Event](s"q-${rapid.Unique()}")))
      val retriever = StandardMemoryRetriever(limit = 5)
      def base(fact: String) = ContextMemory(
        fact = fact,
        label = "indent",
        summary = fact,
        source = MemorySource.Explicit,
        spaceId = MemoryTestSpace,
        key = Some("pref.indent.burst"))
      for {
        first <- TestSigil.upsertMemoryByKey(base("The user prefers tabs for indentation."))
        before <- retriever.retrieve(TestSigil, convId, frames, List(TestUser, TestAgent))
        second <- TestSigil.upsertMemoryByKey(base("The user prefers spaces for indentation."))
        after <- retriever.retrieve(TestSigil, convId, frames, List(TestUser, TestAgent))
      } yield {
        before.memories should contain(first.memory._id)
        after.memories should not contain first.memory._id
        after.memories should contain(second.memory._id)
      }
    }
  }

  "the archive paths" should {
    "leave only the current version's point in the vector index across repeated versioning" in {
      val index = wire(Set(MemoryTestSpace))
      def base(fact: String) = ContextMemory(
        fact = fact,
        label = "target",
        summary = fact,
        source = MemorySource.Explicit,
        spaceId = MemoryTestSpace,
        key = Some("ops.target"))
      for {
        v1 <- TestSigil.upsertMemoryByKey(base("The deploy target is us-east-1."))
        v2 <- TestSigil.upsertMemoryByKey(base("The deploy target is eu-west-2."))
        v3 <- TestSigil.upsertMemoryByKey(base("The deploy target is ap-south-1."))
        vec <- TestHashEmbeddingProvider.embed("The deploy target is us-east-1.")
        hits <- index.search(vec, 20, Map("kind" -> "memory", "spaceId" -> MemoryTestSpace.value))
      } yield {
        val indexed = hits.flatMap(_.payload.get("memoryId")).toSet
        indexed should contain(v3.memory._id.value)
        indexed should not contain v1.memory._id.value
        indexed should not contain v2.memory._id.value
      }
    }

    "drop a rejected memory's point and restore it on approval" in {
      val index = wire(Set(MemoryTestSpace))
      for {
        m <- seed("The office wifi password is hunter2.", MemoryTestSpace)
        vec <- TestHashEmbeddingProvider.embed(m.fact)
        before <- index.search(vec, 10, Map("kind" -> "memory"))
        _ <- TestSigil.rejectMemory(m._id)
        rejected <- index.search(vec, 10, Map("kind" -> "memory"))
        _ <- TestSigil.approveMemory(m._id)
        approved <- index.search(vec, 10, Map("kind" -> "memory"))
      } yield {
        before.flatMap(_.payload.get("memoryId")) should contain(m._id.value)
        rejected.flatMap(_.payload.get("memoryId")) should not contain m._id.value
        approved.flatMap(_.payload.get("memoryId")) should contain(m._id.value)
      }
    }
  }

  "keyed provenance" should {
    "stay bounded across many refreshes of the same slot" in {
      wire(Set(MemoryTestSpace))
      val fact = "The build runs on JDK 21."
      def refresh(i: Int) = TestSigil.upsertMemoryByKey(ContextMemory(
        fact = fact,
        label = "jdk",
        summary = fact,
        source = MemorySource.Explicit,
        spaceId = MemoryTestSpace,
        key = Some("build.jdk"),
        sourceEventIds = List(Id[Event](s"evt-$i"))
      ))
      Task.sequence((1 to 260).toList.map(refresh)).map { results =>
        val last = results.last.memory
        last.sourceEventIds.size shouldBe sigil.MemoryOps.MaxSourceEventIds
        // Order-preserving tail: the most recent refreshes survive.
        last.sourceEventIds.last shouldBe Id[Event]("evt-260")
        last.sourceEventIds should not contain Id[Event]("evt-1")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
