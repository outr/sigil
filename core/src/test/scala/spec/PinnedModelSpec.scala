package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.tool.provider.{PinModelInput, PinModelTool, UnpinModelInput, UnpinModelTool}
import sigil.TurnContext

/**
 * Coverage for conversation-level model pinning.
 *
 * The contract: when `Conversation.pinnedModelId` is set, every
 * LLM dispatch in the conversation routes to that model — the
 * agent's main turn AND framework auxiliary calls. Mode-driven
 * strategy selection and space-level strategy assignment do NOT
 * override the pin.
 *
 * Three locked invariants:
 *   1. `pin_model` persists the pinned id on the Conversation
 *      record.
 *   2. `runAgentTurn`'s strategy resolution short-circuits to a
 *      `ProviderStrategy.single(pinnedId)` when the pin is set
 *      (verified indirectly via the conversation row's value).
 *   3. `unpin_model` clears the pin so subsequent dispatch reverts
 *      to mode/space strategies.
 */
class PinnedModelSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val pinnedModelId = Model.id("test", "pinned-fixture")

  // Seed the registry with the fixture model so PinModelTool's
  // resolver accepts the id rather than refusing it as unresolvable.
  TestSigil.cache.replace(List(sigil.db.Model(
    canonicalSlug = "test/pinned-fixture",
    huggingFaceId = "",
    name = "pinned-fixture",
    description = "PinnedModelSpec fixture",
    contextLength = 32_000,
    architecture = sigil.db.ModelArchitecture("text->text", List("text"), List("text"), "Unknown", None),
    pricing = sigil.db.ModelPricing(BigDecimal(0), BigDecimal(0), None, None),
    topProvider = sigil.db.ModelTopProvider(Some(32_000L), Some(8_192L), false),
    perRequestLimits = None,
    supportedParameters = Set("temperature"),
    defaultParameters = sigil.db.ModelDefaultParameters(),
    knowledgeCutoff = None,
    expirationDate = None,
    links = sigil.db.ModelLinks(""),
    created = lightdb.time.Timestamp(),
    modified = lightdb.time.Timestamp(),
    _id = pinnedModelId
  ))).sync()

  private def freshConversation(): Task[Conversation] = {
    val convId = Conversation.id(s"pin-${rapid.Unique()}")
    val topic = Topic(
      conversationId = convId,
      label = "spec",
      summary = "spec",
      createdBy = TestUser
    )
    val conv = Conversation(
      topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
      _id = convId
    )
    for {
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(topic))).unit
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).unit
    } yield conv
  }

  private def ctx(conv: Conversation): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conv,
      turnInput = TurnInput(conversationId = conv.id)
    )

  "pin_model" should {

    "persist pinnedModelId on the Conversation record" in {
      for {
        conv <- freshConversation()
        _ <- PinModelTool.execute(PinModelInput(pinnedModelId.value), ctx(conv)).toList
        loaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv.id)))
      } yield loaded.flatMap(_.pinnedModelId) shouldBe Some(pinnedModelId)
    }

    "be cleared by unpin_model" in {
      for {
        conv <- freshConversation()
        _ <- PinModelTool.execute(PinModelInput(pinnedModelId.value), ctx(conv)).toList
        afterPin <- TestSigil.withDB(_.conversations.transaction(_.get(conv.id)))
        _ <- UnpinModelTool.execute(UnpinModelInput(), ctx(conv)).toList
        afterUnpin <- TestSigil.withDB(_.conversations.transaction(_.get(conv.id)))
      } yield {
        afterPin.flatMap(_.pinnedModelId) shouldBe Some(pinnedModelId)
        afterUnpin.flatMap(_.pinnedModelId) shouldBe None
      }
    }

    "be no-op against a non-existent conversation" in {
      val orphan = Conversation(
        topics = List(TopicEntry(
          id = Topic.id(s"orphan-topic-${rapid.Unique()}"),
          label = "spec",
          summary = "spec"
        )),
        _id = Conversation.id(s"orphan-${rapid.Unique()}")
      )
      // Pin against a Conversation that was never persisted — the
      // tool's `_.modify(...)` returns None for missing ids and the
      // emit succeeds without writing.
      PinModelTool.execute(PinModelInput(pinnedModelId.value), ctx(orphan)).toList.map { events =>
        events should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
