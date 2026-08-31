package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.conversation.compression.{
  Percentage, StandardContextCurator, StandardContextOptimizer, StandardMemoryRetriever, NoOpBlockExtractor, NoOpContextCompressor
}
import sigil.db.Model
import sigil.event.Message
import sigil.signal.EventState
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import sigil.tool.model.ResponseContent

import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage for the curator's budget tokenizer.
 *
 * Two invariants:
 *
 *   - The budget path never uses the app-wired provider-facing
 *     `tokenizer` — an app that plugs a network-backed tokenizer into
 *     the curator must not pay one HTTP round-trip per frame on
 *     bulk-imported conversations. Asserted via a sentinel that counts
 *     every call against itself.
 *
 *   - Sigil #414 — the budget gate counts with the in-memory BPE
 *     tokenizer by default, not the character heuristic. On
 *     markup-heavy content (CSS / HTML / Liquid) the heuristic
 *     UNDER-counts by 20-30%, which pushed the effective
 *     `Percentage(0.8)` trigger past the model's wire window: the gate
 *     said "fine" all the way to the provider's hard 400, so
 *     compression mathematically never ran (observed live: 200,277
 *     real tokens on a 200K window with zero shed activity).
 */
class CuratorBudgetTokenizerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "budget-tok-model")
  TestSigil.testModel(modelId)

  // Curator's budget shed only runs when `modelFor(modelId)` finds a
  // registered Model. Seed a small-context entry so the path is
  // actually exercised — the count assertion would be meaningless
  // otherwise.
  TestSigil.cache.replace(List(sigil.db.Model(
    canonicalSlug = "test/budget-tok-model",
    huggingFaceId = "",
    name = "budget-tok-model",
    description = "",
    contextLength = 4096L,
    architecture = sigil.db.ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = sigil.db.ModelPricing(
      prompt = BigDecimal(0),
      completion = BigDecimal(0),
      webSearch = None,
      inputCacheRead = None
    ),
    topProvider = sigil.db.ModelTopProvider(
      contextLength = Some(4096L),
      maxCompletionTokens = None,
      isModerated = false
    ),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = sigil.db.ModelLinks(details = ""),
    created = lightdb.time.Timestamp(),
    _id = modelId
  ))).sync()

  /**
   * Counts every call so the test can prove the budget path never used it.
   */
  final private class CountingTokenizer extends Tokenizer {
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

    "keep the budget path off the app-wired `tokenizer` (network-style sentinel)" in {
      val networkTokenizer = new CountingTokenizer
      val convId = Conversation.id(s"budget-tok-${rapid.Unique()}")

      val curator = StandardContextCurator(
        sigil = TestSigil,
        optimizer = StandardContextOptimizer(),
        blockExtractor = NoOpBlockExtractor,
        memoryRetriever = StandardMemoryRetriever(limit = 5),
        compressor = NoOpContextCompressor,
        budget = Percentage(0.8),
        tokenizer = networkTokenizer
      )

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
          Conversation(_id = convId, topics = List(TestTopicEntry))
        )))
        // 80 frames of trivial content — enough that wiring the
        // CountingTokenizer into the budget path would produce a call
        // count well past the assertion threshold, while keeping the
        // bulk-publish setup fast enough to stay clear of the async
        // test timeout under concurrent full-suite load.
        _ <- Task.sequence(
          (1 to 80).toList.map { i =>
            TestSigil.publish(Message(
              participantId = TestUser,
              conversationId = convId,
              topicId = TestTopicEntry.id,
              content = Vector(ResponseContent.Text(s"frame body $i")),
              state = EventState.Complete
            ))
          }
        )
        _ <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
      } yield
        // The budget path uses budgetTokenizer (HeuristicTokenizer by
        // default). Calls into the sentinel must be bounded — the
        // warning path may legitimately count a few times against
        // `tokenizer`, but the 80-frame budget pass must never touch it.
        networkTokenizer.calls.get should be < 50
    }

    "use the explicitly-passed budgetTokenizer when an app opts in" in {
      val budgetSentinel = new CountingTokenizer
      val convId = Conversation.id(s"budget-tok-opt-${rapid.Unique()}")

      val curator = StandardContextCurator(
        sigil = TestSigil,
        optimizer = StandardContextOptimizer(),
        blockExtractor = NoOpBlockExtractor,
        memoryRetriever = StandardMemoryRetriever(limit = 5),
        compressor = NoOpContextCompressor,
        budget = Percentage(0.8),
        budgetTokenizer = budgetSentinel
      )

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
          Conversation(_id = convId, topics = List(TestTopicEntry))
        )))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("one short frame")),
          state = EventState.Complete
        ))
        _ <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
      } yield
        // The explicit budgetTokenizer is in play — at least the one
        // frame above flowed through it.
        budgetSentinel.calls.get should be >= 1
    }

    "default to the in-memory BPE tokenizer (sigil #414)" in Task {
      sigil.tokenize.JtokkitTokenizer.available shouldBe true
      StandardContextCurator(TestSigil).budgetTokenizer shouldBe sigil.tokenize.JtokkitTokenizer.OpenAIO200k
    }

    "shed markup-heavy content the heuristic gate would wave past the wire window (sigil #414)" in {
      // Dense, non-repeating CSS — the content class where real BPE counts
      // run far above the ~3.5 chars/token heuristic. Varied hex colors /
      // fractional dimensions per line keep BPE merges from amortising the
      // way they would on repeated identical rules.
      def hex(i: Int, salt: Int): String = f"${(i * 2654435761L + salt * 40503L) & 0xffffff}%06x"
      val cssBlock = (0 until 24).map { i =>
        s""".pg__it-x${hex(i, 1).take(4)}{margin:${i % 7}.${i % 10}px;padding:${(i * 3) % 11}px ${(i * 5) % 13}px;color:#${hex(
            i,
            2)};background:#${hex(i, 3)};flex-basis:calc(${23 + i % 41}% - ${i % 9}.${i % 4}px);}
           |#cb-${hex(i, 4).take(5)}::after{content:"\\2192";top:-${i % 5}.${i % 8}px;border:1px solid #${hex(
            i,
            5)};transform:translate(${i % 17}.${i % 6}px,${i % 13}.${i % 7}px) rotate(${i * 7 % 360}deg);}
           |@media(max-width:${548 + i * 13}px){.pg__it-x${hex(i, 1).take(4)}{grid-template-columns:repeat(${1 + i % 5},minmax(${40 +
            i % 27}px,1fr));gap:${i % 11}.${i % 5}px;}}
           |""".stripMargin
      }.mkString
      val convId = Conversation.id(s"budget-tok-markup-${rapid.Unique()}")
      val frames = (0 until 60).toVector.map { i =>
        val who: sigil.participant.ParticipantId = if (i % 2 == 0) TestUser else TestAgent
        sigil.conversation.ContextFrame.Text(s"snippet $i:\n$cssBlock", who, Id[sigil.event.Event](s"css-$i"))
      }
      val input = sigil.conversation.TurnInput(conversationId = convId, frames = frames)
      val totalText = frames.collect { case t: sigil.conversation.ContextFrame.Text => t.content }.mkString("\n")
      val hCount = HeuristicTokenizer.count(totalText)
      val jCount = sigil.tokenize.JtokkitTokenizer.OpenAIO200k.count(totalText)
      // The fixture must exhibit the real-world divergence, or the test
      // proves nothing.
      (jCount.toDouble / hCount.toDouble) should be > 1.2
      // A window whose 80% cap sits BETWEEN the two counts — the heuristic
      // gate says "fits", the real count says "over".
      val cap = (hCount + jCount) / 2
      val markupModelId = Model.id("test", s"budget-tok-markup-${rapid.Unique()}")
      for {
        _ <- TestSigil.cache.merge(List(sigil.db.Model(
          canonicalSlug = markupModelId.value,
          huggingFaceId = "",
          name = "budget-tok-markup",
          description = "",
          contextLength = (cap / 0.8).toLong,
          architecture = sigil.db.ModelArchitecture(
            modality = "text->text",
            inputModalities = List("text"),
            outputModalities = List("text"),
            tokenizer = "None",
            instructType = None),
          pricing = sigil.db.ModelPricing(BigDecimal(0), BigDecimal(0), None, None),
          topProvider = sigil.db.ModelTopProvider(Some((cap / 0.8).toLong), None, false),
          perRequestLimits = None,
          supportedParameters = Set.empty,
          knowledgeCutoff = None,
          expirationDate = None,
          links = sigil.db.ModelLinks(details = ""),
          created = lightdb.time.Timestamp(),
          _id = markupModelId
        )))
        defaultOut <- StandardContextCurator(TestSigil)
          .refit(input, markupModelId, List(TestUser, TestAgent))
        heuristicOut <- StandardContextCurator(TestSigil, budgetTokenizer = HeuristicTokenizer)
          .refit(input, markupModelId, List(TestUser, TestAgent))
      } yield {
        import sigil.conversation.compression.TokenEstimator
        val bpe = sigil.tokenize.JtokkitTokenizer.OpenAIO200k
        // Heuristic gate: estimate under cap → input passes through
        // untouched — the pre-fix behaviour that let conversations grow
        // to the provider's hard reject.
        heuristicOut.frames shouldBe input.frames
        // BPE gate: real count over cap → the shed cascade engages
        // (frame elision and/or shed) and the surviving content fits
        // under the cap by real-token count.
        val outTokens = TokenEstimator.estimateFrames(defaultOut.frames, bpe)
        outTokens should be < jCount
        outTokens should be <= cap
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
