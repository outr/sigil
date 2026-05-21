package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Message
import sigil.provider.TokenUsage
import sigil.signal.{ConversationCostUpdated, EventState, Signal}
import sigil.tool.model.ResponseContent

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Coverage for bug #4 — `Message.modelId` attribution + per-conversation
 * cost surface. Three flavors:
 *
 *   1. Round-trip — a Message published with a `modelId` round-trips
 *      through the event store with the field intact.
 *   2. Cost projection — two settled Messages with a known model
 *      increment `Conversation.cost` correctly and emit
 *      `ConversationCostUpdated` Notices with matching delta + total.
 *   3. No-modelId / unknown-model case — a Message without `modelId`
 *      (or with one not in the registry) leaves cost at zero and
 *      emits no Notice.
 */
class MessageModelIdAndCostSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Concrete pricing so the math is easy to assert. Per-token USD —
    * matches `ModelPricing` semantics. */
  private val pricing: ModelPricing = ModelPricing(
    prompt = BigDecimal("0.000001"),     // 1e-6 USD per prompt token (i.e. $1 / M tokens)
    completion = BigDecimal("0.000002"), // 2e-6 USD per completion token
    webSearch = None,
    inputCacheRead = None
  )

  private val pricedModelId: Id[Model] = Model.id("test", "cost-spec-model")

  private val priced: Model = Model(
    canonicalSlug = "test/cost-spec-model",
    huggingFaceId = "",
    name = "cost-spec-model",
    description = "Synthetic priced model for cost-projection tests",
    contextLength = 4096L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = pricing,
    topProvider = ModelTopProvider(contextLength = Some(4096L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = pricedModelId
  )

  // Seed once. Multiple specs share TestSigil's registry, so use merge
  // (additive) — replace would clobber other specs' fixtures.
  TestSigil.cache.merge(List(priced)).sync()

  /** Ensure a Conversation row exists in the DB. The cost projection
    * runs inside `_.modify(conversationId)` which is a no-op when no
    * row is present — for the projection assertion we need the row. */
  private def seedConversation(convId: Id[Conversation]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(topics = TestTopicStack, _id = convId)
    ))).unit

  private def settledMessage(convId: Id[Conversation],
                             prompt: Int,
                             completion: Int,
                             modelId: Option[Id[Model]]): Message =
    Message(
      participantId = TestAgent,
      conversationId = convId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text("hi")),
      usage = TokenUsage(prompt, completion, prompt + completion),
      modelId = modelId,
      state = EventState.Complete
    )

  /** Subscribe to the signal stream — SignalHub registers eagerly, so
    * signals published after this call are captured. */
  private def subscribe(): (ConcurrentLinkedQueue[Signal], () => Unit) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    @volatile var running = true
    TestSigil.signals
      .takeWhile(_ => running)
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    (recorded, () => running = false)
  }

  private def costNotices(recorded: ConcurrentLinkedQueue[Signal], convId: Id[Conversation]): List[ConversationCostUpdated] =
    recorded.iterator().asScala.collect { case n: ConversationCostUpdated if n.conversationId == convId => n }.toList

  /** Poll until `count` cost notices for `convId` have landed. */
  private def awaitCostNotices(recorded: ConcurrentLinkedQueue[Signal], convId: Id[Conversation], count: Int,
                               timeout: FiniteDuration = 5.seconds): Task[List[ConversationCostUpdated]] = {
    def loop(remainingMs: Long): Task[List[ConversationCostUpdated]] = {
      val cur = costNotices(recorded, convId)
      if (cur.size >= count || remainingMs <= 0) Task.pure(cur)
      else Task.sleep(20.millis).flatMap(_ => loop(remainingMs - 20))
    }
    loop(timeout.toMillis)
  }

  "Message.modelId" should {

    "round-trip through the event store via Sigil.publish" in {
      val convId = Conversation.id(s"modelid-roundtrip-${rapid.Unique()}")
      val msg = settledMessage(convId, prompt = 10, completion = 5, modelId = Some(pricedModelId))
      for {
        _ <- seedConversation(convId)
        _ <- TestSigil.publish(msg)
        loaded <- TestSigil.withDB(_.events.transaction(_.get(msg._id)))
      } yield {
        loaded.collect { case m: Message => m.modelId } shouldBe Some(Some(pricedModelId))
      }
    }
  }

  "Conversation.cost projection" should {

    "increment by per-Message charge for each settled Message with a known modelId, and fire a ConversationCostUpdated Notice" in {
      val convId = Conversation.id(s"cost-sum-${rapid.Unique()}")
      // First message: 100 prompt + 50 completion tokens.
      val m1 = settledMessage(convId, prompt = 100, completion = 50, modelId = Some(pricedModelId))
      // Second: 200 prompt + 75 completion tokens.
      val m2 = settledMessage(convId, prompt = 200, completion = 75, modelId = Some(pricedModelId))

      val expectedDelta1 = pricing.prompt * 100 + pricing.completion * 50
      val expectedDelta2 = pricing.prompt * 200 + pricing.completion * 75
      val expectedTotal  = expectedDelta1 + expectedDelta2

      val (recorded, stop) = subscribe()
      for {
        _ <- seedConversation(convId)
        _ <- TestSigil.publish(m1)
        _ <- TestSigil.publish(m2)
        notices <- awaitCostNotices(recorded, convId, 2)
        loaded <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        stop()
        loaded.map(_.cost) shouldBe Some(expectedTotal)
        notices should have size 2
        notices.head.delta shouldBe expectedDelta1
        notices.head.cost shouldBe expectedDelta1
        notices(1).delta shouldBe expectedDelta2
        notices(1).cost shouldBe expectedTotal
      }
    }

    "leave cost at zero and emit no Notice when modelId is None" in {
      val convId = Conversation.id(s"cost-no-model-${rapid.Unique()}")
      val fenceConvId = Conversation.id(s"cost-no-model-fence-${rapid.Unique()}")
      val msg = settledMessage(convId, prompt = 50, completion = 25, modelId = None)
      val fence = settledMessage(fenceConvId, prompt = 1, completion = 1, modelId = Some(pricedModelId))

      val (recorded, stop) = subscribe()
      for {
        _ <- seedConversation(convId)
        _ <- seedConversation(fenceConvId)
        _ <- TestSigil.publish(msg)
        // Fence: a priced Message that DOES fire a notice — once it
        // lands, FIFO ordering guarantees a wrongly-emitted notice for
        // `convId` would already be present too.
        _ <- TestSigil.publish(fence)
        _ <- awaitCostNotices(recorded, fenceConvId, 1)
        loaded <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        stop()
        loaded.map(_.cost) shouldBe Some(BigDecimal(0))
        costNotices(recorded, convId) shouldBe empty
      }
    }

    "resolve a bare modelId against the prefixed registry entry (#91)" in {
      // Message stamps `cost-spec-model` (bare); registry indexes
      // `test/cost-spec-model` (prefixed). Pre-#91 cache.find missed
      // and the projection silently zeroed the cost.
      val convId = Conversation.id(s"cost-bare-${rapid.Unique()}")
      val bareId = Id[Model]("cost-spec-model")
      val msg = settledMessage(convId, prompt = 80, completion = 40, modelId = Some(bareId))

      val expectedDelta = pricing.prompt * 80 + pricing.completion * 40

      val (recorded, stop) = subscribe()
      for {
        _ <- seedConversation(convId)
        _ <- TestSigil.publish(msg)
        notices <- awaitCostNotices(recorded, convId, 1)
        loaded <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        stop()
        loaded.map(_.cost) shouldBe Some(expectedDelta)
        notices should have size 1
        notices.head.delta shouldBe expectedDelta
      }
    }

    "leave cost at zero when the model is unknown to the registry" in {
      val convId = Conversation.id(s"cost-unknown-model-${rapid.Unique()}")
      val fenceConvId = Conversation.id(s"cost-unknown-fence-${rapid.Unique()}")
      val unknown = Model.id("test", "not-in-registry")
      val msg = settledMessage(convId, prompt = 100, completion = 50, modelId = Some(unknown))
      val fence = settledMessage(fenceConvId, prompt = 1, completion = 1, modelId = Some(pricedModelId))

      val (recorded, stop) = subscribe()
      for {
        _ <- seedConversation(convId)
        _ <- seedConversation(fenceConvId)
        _ <- TestSigil.publish(msg)
        _ <- TestSigil.publish(fence)
        _ <- awaitCostNotices(recorded, fenceConvId, 1)
        loaded <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        stop()
        loaded.map(_.cost) shouldBe Some(BigDecimal(0))
        costNotices(recorded, convId) shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
