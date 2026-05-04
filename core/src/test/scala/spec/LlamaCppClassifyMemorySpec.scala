package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{
  ContextMemory, Conversation, MemorySource, TopicEntry
}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Message
import sigil.signal.EventState
import sigil.provider.llamacpp.LlamaCppProvider

/**
 * Live-LLM coverage for the unified [[sigil.tool.consult.ClassifyMemoryTool]].
 * Drives `Sigil.persistMemoryFor` against real RocksDB + llama.cpp; the
 * classifier sees the memory + accessible spaces (with metadata) +
 * the user's most recent message text and decides:
 *
 *   1. Imperative phrasing in the user message (`"always X"`, `"never Y"`)
 *      → memory persists with `pinned = true`.
 *   2. Soft / factual phrasing without imperatives → memory stays
 *      `pinned = false`.
 */
class LlamaCppClassifyMemorySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

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

  private def freshConversation(label: String, userMsg: String): Id[Conversation] = {
    val id = Conversation.id(s"$label-${rapid.Unique()}")
    val topic = TopicEntry(
      id = sigil.conversation.Topic.id(s"topic-$label"),
      label = "Scala backend conventions",
      summary = "User is establishing rules for the team's Scala backend codebase."
    )
    TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
      _id = id,
      topics = List(topic)
    )))).sync()
    // Seed a user message so the classifier has imperative-cue context.
    TestSigil.publish(Message(
      participantId = TestUser,
      conversationId = id,
      topicId = topic.id,
      content = Vector(sigil.tool.model.ResponseContent.Text(userMsg)),
      state = EventState.Complete
    )).sync()
    id
  }

  private def reseed(): Unit = {
    TestSigil.reset()
    TestSigil.setProvider(Task.pure(LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)))
    TestSigil.setMemoryClassifierModel(Some(modelId))
    TestSigil.setAccessibleSpaces(_ => Task.pure(Set(MemoryTestSpace)))
    TestSigil.withDB(_.memories.transaction { tx =>
      tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
    }).sync()
  }

  "ClassifyMemoryTool" should {
    "pin a memory when the user's recent message uses imperative phrasing" in {
      reseed()
      val convId = freshConversation("imperative",
        userMsg = "From now on, always use rapid Streams for concurrency in our Scala backend code.")
      val draft = ContextMemory(
        fact = "The team's Scala backend uses rapid Streams for concurrency.",
        label = "scala-concurrency",
        summary = "Always use rapid Streams for concurrency.",
        source = MemorySource.Explicit,
        spaceId = sigil.GlobalSpace,
        conversationId = Some(convId)
      )
      TestSigil.persistMemoryFor(draft, List(TestUser, TestAgent), convId).map { stored =>
        withClue(s"keywords=${stored.keywords.mkString(",")} pinned=${stored.pinned} space=${stored.spaceId.value}: ") {
          stored.pinned shouldBe true
          stored.keywords should not be empty
        }
      }
    }

    "leave a memory unpinned when phrasing is non-imperative" in {
      reseed()
      val convId = freshConversation("soft",
        userMsg = "By the way, I tend to prefer the rapid library for backend Scala work.")
      val draft = ContextMemory(
        fact = "User tends to prefer rapid for backend Scala work.",
        label = "soft-pref",
        summary = "Soft preference for rapid in backend Scala.",
        source = MemorySource.Explicit,
        spaceId = sigil.GlobalSpace,
        conversationId = Some(convId)
      )
      TestSigil.persistMemoryFor(draft, List(TestUser, TestAgent), convId).map { stored =>
        withClue(s"keywords=${stored.keywords.mkString(",")} pinned=${stored.pinned} space=${stored.spaceId.value}: ") {
          // Soft preference shouldn't pin.
          stored.pinned shouldBe false
          stored.keywords should not be empty
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
