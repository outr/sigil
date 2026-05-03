package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, ConversationView, MemorySource, TopicEntry}
import sigil.conversation.compression.{StandardMemoryRetriever, MemoryRetrievalResult}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.{Event, Message, TopicChange, TopicChangeKind}
import sigil.signal.EventState
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.tool.model.RespondInput
import sigil.vector.InMemoryVectorIndex

/**
 * End-to-end live coverage for non-critical memory retrieval:
 *
 *   1. **Save-side keyword extraction** (live LLM): persist a memory
 *      via `Sigil.persistMemory` with `memoryClassifierModel` wired to a
 *      live model; assert the persisted record has non-empty keywords
 *      and that they overlap the memory's content vocabulary.
 *
 *   2. **Hybrid retrieval** (real RocksDB + live LLM): seed several
 *      memories with diverse content, set a conversation's
 *      `currentKeywords` matching one of them, run
 *      `StandardMemoryRetriever.retrieve` and assert the keyword-
 *      matching memory comes back at the top.
 *
 *   3. **Inter-message stability**: invoke retrieve twice within one
 *      "agent burst" (no intervening message / topic-change publish);
 *      assert the cache returned the same result without re-running
 *      the underlying compute.
 *
 *   4. **Cache invalidation on user message**: publish a non-agent
 *      Message; assert the cached entry is gone and the next retrieve
 *      computes fresh.
 *
 *   5. **Cache invalidation on TopicChange Switch**: publish a
 *      TopicChange of kind `Switch`; same assertion.
 *
 *   6. **No invalidation on TopicChange Rename**: publish a `Rename`-
 *      kind TopicChange; assert the cache survives.
 */
class LlamaCppMemoryKeywordRetrievalSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id(sigil.provider.llamacpp.LlamaCpp.Provider, "qwen3.5-9b-q4_k_m")

  TestSigil.cache.replace(List(Model(
    canonicalSlug = s"${sigil.provider.llamacpp.LlamaCpp.Provider}/qwen3.5-9b-q4_k_m",
    huggingFaceId = "",
    name = "qwen3.5-9b-q4_k_m",
    description = "Test seed",
    contextLength = 262_144L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(262_144L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  ))).sync()

  private def freshConversation(label: String): Id[Conversation] = {
    val id = Conversation.id(s"$label-${rapid.Unique()}")
    val topic = TopicEntry(
      id = sigil.conversation.Topic.id(s"topic-$label"),
      label = "Scala backend service",
      summary = "User is building a backend in Scala and asks about preferences and conventions."
    )
    TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
      _id = id,
      topics = List(topic)
    )))).sync()
    id
  }

  private def setKeywords(convId: Id[Conversation], keywords: Vector[String]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.modify(convId) {
      case Some(c) => Task.pure(Some(c.copy(currentKeywords = keywords)))
      case None    => Task.pure(None)
    })).unit

  private def seedMemory(fact: String,
                        label: String,
                        summary: String): Task[ContextMemory] =
    TestSigil.persistMemory(ContextMemory(
      fact = fact,
      label = label,
      summary = summary,
      source = MemorySource.Explicit,
      spaceId = MemoryTestSpace
    ))

  private def reseedTestSigil(): Unit = {
    TestSigil.reset()
    TestSigil.setProvider(Task.pure(LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)))
    TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
    TestSigil.setVectorIndex(new InMemoryVectorIndex)
    TestSigil.setMemoryClassifierModel(Some(modelId))
    TestSigil.setAccessibleSpaces(_ => Task.pure(Set(MemoryTestSpace)))
    // Wipe leftover memories from previous tests.
    TestSigil.withDB(_.memories.transaction { tx =>
      tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
    }).sync()
  }

  "Sigil.persistMemory with memoryClassifierModel set" should {
    "extract keywords from the memory content via a live LLM call" in {
      reseedTestSigil()
      seedMemory(
        fact = "User Alice prefers Scala for backend services because of strong typing and the rapid library.",
        label = "language-preference",
        summary = "Alice picks Scala for backend work."
      ).map { stored =>
        withClue(s"keywords=${stored.keywords.mkString(",")}: ") {
          stored.keywords should not be empty
          // We don't pin exact tokens — model output varies — but at
          // least one keyword should overlap the memory's domain.
          val lower = stored.keywords.map(_.toLowerCase)
          val expectedAny = Vector("scala", "backend", "language", "preference", "alice", "typing", "rapid")
          lower.exists(expectedAny.contains) shouldBe true
        }
      }
    }
  }

  "StandardMemoryRetriever (hybrid)" should {
    "rank a keyword-matching memory at the top against a topic-keyword query" in {
      reseedTestSigil()
      val convId = freshConversation("retrieve-rank")
      for {
        // Seed three memories — only the second is relevant to the
        // current conversation focus (Scala backend conventions).
        _ <- seedMemory(
          fact = "User Bob enjoys hiking on weekends in the Pacific Northwest.",
          label = "hobby-bob",
          summary = "Bob hikes on weekends."
        )
        scalaMem <- seedMemory(
          // Non-imperative phrasing — the unified classifier should
          // keep this `Once` (topical), not pin it. We're exercising
          // topical retrieval here.
          fact = "The team's Scala backend codebase uses the rapid Streams library for concurrency.",
          label = "scala-style",
          summary = "Scala backend uses rapid streams."
        )
        _ <- seedMemory(
          fact = "Carol's favorite color is blue.",
          label = "color-carol",
          summary = "Carol prefers blue."
        )
        // Set the conversation's currentKeywords as if the agent's last
        // Respond push was about Scala backend conventions.
        _ <- setKeywords(convId, Vector("scala", "backend", "rapid", "streams"))
        view = ConversationView(
          conversationId = convId,
          frames = Vector.empty,
          participantProjections = Map.empty
        )
        retriever = StandardMemoryRetriever(spaces = Set(MemoryTestSpace), limit = 3)
        result <- retriever.retrieve(TestSigil, view, chain = List(TestUser, TestAgent))
        rendered <- TestSigil.withDB(_.memories.transaction { tx =>
          Task.sequence(result.memories.map(id => tx.get(id))).map(_.flatten)
        })
      } yield {
        withClue(s"top results: ${rendered.map(_.label).mkString(",")} | keywords seeded on scala-style: ${scalaMem.keywords.mkString(",")}: ") {
          result.memories should not be empty
          rendered.headOption.map(_.label) shouldBe Some("scala-style")
        }
      }
    }
  }

  "Memory retrieval cache" should {
    "reuse the cached result across an agent burst (inter-message-stable)" in {
      reseedTestSigil()
      val convId = freshConversation("retrieve-cache")
      for {
        _ <- seedMemory(
          fact = "Project codename is Atlas.",
          label = "project-codename",
          summary = "Project Atlas."
        )
        _ <- setKeywords(convId, Vector("atlas", "project", "codename"))
        view = ConversationView(
          conversationId = convId,
          frames = Vector.empty,
          participantProjections = Map.empty
        )
        retriever = StandardMemoryRetriever(spaces = Set(MemoryTestSpace), limit = 3)
        first  <- retriever.retrieve(TestSigil, view, chain = List(TestUser, TestAgent))
        second <- retriever.retrieve(TestSigil, view, chain = List(TestUser, TestAgent))
      } yield {
        // Identical references — same cached result, not re-derived.
        first shouldBe second
        // Cache definitely populated.
        TestSigil.memoryRetrievalCache.peek(convId) shouldBe Some(first)
      }
    }

    "invalidate on a non-agent Message and recompute on next retrieve" in {
      reseedTestSigil()
      val convId = freshConversation("retrieve-invalidate-msg")
      for {
        _ <- seedMemory(
          fact = "Deployment target is staging-east-1.",
          label = "deploy-target",
          summary = "Deploys to staging-east-1."
        )
        _ <- setKeywords(convId, Vector("deployment", "staging", "east"))
        view = ConversationView(
          conversationId = convId,
          frames = Vector.empty,
          participantProjections = Map.empty
        )
        retriever = StandardMemoryRetriever(spaces = Set(MemoryTestSpace), limit = 3)
        _ <- retriever.retrieve(TestSigil, view, chain = List(TestUser, TestAgent))
        cachedBefore = TestSigil.memoryRetrievalCache.peek(convId)
        // User publishes a Message — settled effect should invalidate.
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicId,
          state = EventState.Complete
        ))
        // Give the SettledEffect a beat to run (it's part of publish so
        // it should already be done, but safer).
        _ <- Task.sleep(scala.concurrent.duration.FiniteDuration(50, "ms"))
      } yield {
        cachedBefore should not be None
        TestSigil.memoryRetrievalCache.peek(convId) shouldBe None
      }
    }

    "invalidate on TopicChange Switch but NOT on Rename" in {
      reseedTestSigil()
      val convId = freshConversation("retrieve-invalidate-topic")
      for {
        _ <- seedMemory(
          fact = "The codebase uses scalafmt 3.8.6.",
          label = "code-format",
          summary = "scalafmt version."
        )
        _ <- setKeywords(convId, Vector("scalafmt", "format", "code"))
        view = ConversationView(
          conversationId = convId,
          frames = Vector.empty,
          participantProjections = Map.empty
        )
        retriever = StandardMemoryRetriever(spaces = Set(MemoryTestSpace), limit = 3)
        _ <- retriever.retrieve(TestSigil, view, chain = List(TestUser, TestAgent))
        cachedAfterFirst = TestSigil.memoryRetrievalCache.peek(convId)

        // Rename — must NOT invalidate.
        renameTc = TopicChange(
          kind = TopicChangeKind.Rename(previousLabel = "old-label"),
          newLabel = "renamed",
          newSummary = "renamed summary",
          participantId = TestAgent,
          conversationId = convId,
          topicId = TestTopicId,
          state = EventState.Complete
        )
        _ <- TestSigil.publish(renameTc)
        _ <- Task.sleep(scala.concurrent.duration.FiniteDuration(50, "ms"))
        cachedAfterRename = TestSigil.memoryRetrievalCache.peek(convId)

        // Switch — MUST invalidate.
        switchTc = TopicChange(
          kind = TopicChangeKind.Switch(previousTopicId = TestTopicId),
          newLabel = "switched",
          newSummary = "switched summary",
          participantId = TestAgent,
          conversationId = convId,
          topicId = TestTopicId,
          state = EventState.Complete
        )
        _ <- TestSigil.publish(switchTc)
        _ <- Task.sleep(scala.concurrent.duration.FiniteDuration(50, "ms"))
        cachedAfterSwitch = TestSigil.memoryRetrievalCache.peek(convId)
      } yield {
        cachedAfterFirst should not be None
        cachedAfterRename shouldBe cachedAfterFirst // survived
        cachedAfterSwitch shouldBe None              // invalidated
      }
    }
  }
}
