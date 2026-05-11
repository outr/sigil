package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.conversation.compression.{Percentage, StandardContextCurator, StandardContextOptimizer, StandardMemoryRetriever, NoOpBlockExtractor, NoOpContextCompressor}
import sigil.db.Model
import sigil.event.Message
import sigil.signal.EventState
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import sigil.tool.model.ResponseContent

import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage for the budget-vs-warning tokenizer split. The curator's
 * `budgetTokenizer` defaults to [[HeuristicTokenizer]] regardless of
 * what an app wires for the provider-facing `tokenizer`, so an app
 * that plugs a network-backed tokenizer into the curator doesn't pay
 * one HTTP round-trip per frame on bulk-imported conversations.
 *
 * Asserts the count delegation directly: a sentinel `Tokenizer`
 * counts every call against itself, so the test can prove the
 * budget path never invoked it.
 */
class CuratorBudgetTokenizerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "budget-tok-model")

  // Curator's budget shed only runs when `modelFor(modelId)` finds a
  // registered Model. Seed a small-context entry so the path is
  // actually exercised — the count assertion would be meaningless
  // otherwise.
  TestSigil.cache.replace(List(sigil.db.Model(
    canonicalSlug    = "test/budget-tok-model",
    huggingFaceId    = "",
    name             = "budget-tok-model",
    description      = "",
    contextLength    = 4096L,
    architecture     = sigil.db.ModelArchitecture(
      modality         = "text->text",
      inputModalities  = List("text"),
      outputModalities = List("text"),
      tokenizer        = "None",
      instructType     = None
    ),
    pricing          = sigil.db.ModelPricing(
      prompt = BigDecimal(0), completion = BigDecimal(0),
      webSearch = None, inputCacheRead = None
    ),
    topProvider      = sigil.db.ModelTopProvider(
      contextLength       = Some(4096L),
      maxCompletionTokens = None,
      isModerated         = false
    ),
    perRequestLimits    = None,
    supportedParameters = Set.empty,
    knowledgeCutoff     = None,
    expirationDate      = None,
    links               = sigil.db.ModelLinks(details = ""),
    created             = lightdb.time.Timestamp(),
    _id                 = modelId
  ))).sync()

  /** Counts every call so the test can prove the budget path never used it. */
  private final class CountingTokenizer extends Tokenizer {
    val calls = new AtomicInteger(0)
    override def count(text: String): Int = {
      calls.incrementAndGet()
      // Slow on purpose so a regression that wires this back into the
      // budget path makes the test wall-clock-slow as well as
      // count-wrong.
      Thread.sleep(2)
      text.length / 4
    }
  }

  "StandardContextCurator.budgetTokenizer" should {

    "default to HeuristicTokenizer even when `tokenizer` is a network-style sentinel" in {
      val networkTokenizer = new CountingTokenizer
      val convId = Conversation.id(s"budget-tok-${rapid.Unique()}")

      val curator = StandardContextCurator(
        sigil           = TestSigil,
        optimizer       = StandardContextOptimizer(),
        blockExtractor  = NoOpBlockExtractor,
        memoryRetriever = StandardMemoryRetriever(limit = 5),
        compressor      = NoOpContextCompressor,
        budget          = Percentage(0.8),
        tokenizer       = networkTokenizer
      )

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
               Conversation(_id = convId, topics = List(TestTopicEntry))
             )))
        // 200 frames of trivial content — enough that wiring the
        // CountingTokenizer into the budget path would produce a
        // very visible call count (and wall-clock penalty).
        _ <- Task.sequence(
               (1 to 200).toList.map { i =>
                 TestSigil.publish(Message(
                   participantId  = TestUser,
                   conversationId = convId,
                   topicId        = TestTopicEntry.id,
                   content        = Vector(ResponseContent.Text(s"frame body $i")),
                   state          = EventState.Complete
                 ))
               }
             )
        _ <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
      } yield {
        // The budget path uses budgetTokenizer (HeuristicTokenizer by
        // default). Calls into the network-style sentinel must be
        // bounded — the warning path may legitimately count a few
        // times against `tokenizer`, but the 200-frame budget pass
        // must never touch it.
        networkTokenizer.calls.get should be < 50
      }
    }

    "use the explicitly-passed budgetTokenizer when an app opts in" in {
      val budgetSentinel = new CountingTokenizer
      val convId = Conversation.id(s"budget-tok-opt-${rapid.Unique()}")

      val curator = StandardContextCurator(
        sigil           = TestSigil,
        optimizer       = StandardContextOptimizer(),
        blockExtractor  = NoOpBlockExtractor,
        memoryRetriever = StandardMemoryRetriever(limit = 5),
        compressor      = NoOpContextCompressor,
        budget          = Percentage(0.8),
        budgetTokenizer = budgetSentinel
      )

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
               Conversation(_id = convId, topics = List(TestTopicEntry))
             )))
        _ <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("one short frame")),
               state          = EventState.Complete
             ))
        _ <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
      } yield {
        // The explicit budgetTokenizer is in play — at least the one
        // frame above flowed through it.
        budgetSentinel.calls.get should be >= 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
