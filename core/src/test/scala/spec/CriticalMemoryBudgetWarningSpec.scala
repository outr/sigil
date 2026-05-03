package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, Sigil}
import sigil.conversation.{
  ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, ConversationView, MemorySource
}
import sigil.conversation.compression.{
  ContextCompressor, MemoryRetrievalResult, MemoryRetriever, NoOpContextCompressor, Percentage,
  StandardContextCurator
}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.participant.ParticipantId

/**
 * Coverage for the in-conversation budget warning emitted by the
 * curator when Critical-memory share crosses
 * [[StandardContextCurator.criticalShareWarningThreshold]]. The
 * warning lives in `TurnInput.extraContext(_budgetWarning)`; the
 * agent reads it on its next turn alongside the rest of the system
 * prompt and can mention to the user / call the introspection
 * tools.
 */
class CriticalMemoryBudgetWarningSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Id[Model]("test/warning-spec")

  private val tinyModel: Model = Model(
    canonicalSlug = "test/warning-spec",
    huggingFaceId = "",
    name = "warning-spec",
    description = "Synthetic model for warning-injection test",
    contextLength = 4000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(4000L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  )

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestSigil.cache.merge(List(tinyModel)).sync()
  }

  private class FixedRetriever(critical: Vector[Id[ContextMemory]]) extends MemoryRetriever {
    override def retrieve(sigilArg: Sigil,
                          view: ConversationView,
                          chain: List[ParticipantId]): Task[MemoryRetrievalResult] =
      Task.pure(MemoryRetrievalResult(memories = Vector.empty, criticalMemories = critical))
  }

  "StandardContextCurator" should {

    "inject a `_budgetWarning` extraContext entry when critical share exceeds threshold" in {
      // Critical memory weighs ~2000 heuristic tokens (8000 chars / 4),
      // 50% of the model's 4000-tok context — well above the 30% default
      // threshold.
      val convId = Conversation.id(s"warn-${rapid.Unique()}")
      val heavyCritical = ContextMemory(
        fact = ("This is a verbose persistent directive across many sentences. " * 130).trim,
        source = MemorySource.Explicit, pinned = true,
        spaceId = GlobalSpace,
        key = s"crit-heavy-${rapid.Unique()}"
      )
      val curator = StandardContextCurator(
        sigil = TestSigil,
        memoryRetriever = new FixedRetriever(Vector(heavyCritical._id))
      )
      val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))

      // Override the cap during this test — the heavy critical would
      // otherwise be rejected by Phase 3's CoreContextCapSpec validator.
      // Insert directly via the DB transaction to bypass the cap.
      TestSigil.withDB(_.memories.transaction(_.upsert(heavyCritical))).flatMap { _ =>
        curator.curate(view, modelId, List(TestUser, TestAgent))
      }.map { turnInput =>
        val key = ContextKey("_budgetWarning")
        turnInput.extraContext should contain key (key)
        val msg = turnInput.extraContext(key)
        msg should include("critical directives")
        msg should include("list_pinned_memories")
      }
    }

    "NOT inject the warning when critical share is below threshold" in {
      val convId = Conversation.id(s"no-warn-${rapid.Unique()}")
      val tinyCritical = ContextMemory(
        fact = "Be concise.",
        source = MemorySource.Explicit, pinned = true,
        spaceId = GlobalSpace,
        key = s"crit-tiny-${rapid.Unique()}"
      )
      val curator = StandardContextCurator(
        sigil = TestSigil,
        memoryRetriever = new FixedRetriever(Vector(tinyCritical._id))
      )
      val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))

      // Bypass the cap validator (already exercised in CoreContextCapSpec)
      // and insert directly. The warning check is independent of the cap.
      TestSigil.withDB(_.memories.transaction(_.upsert(tinyCritical))).flatMap { _ =>
        curator.curate(view, modelId, List(TestUser, TestAgent))
      }.map { turnInput =>
        turnInput.extraContext should not contain key (ContextKey("_budgetWarning"))
      }
    }
  }
}
