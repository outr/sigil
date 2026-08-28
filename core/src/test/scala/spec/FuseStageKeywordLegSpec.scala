package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextMemory, Conversation, MemorySource}
import sigil.conversation.compression.retrieval.{FuseStage, MemoryRetrievalContext, MemoryRetrievalState}

/**
 * The context keyword leg's fusion semantics: it contributes to the
 * ranking at [[FuseStage.keywordWeight]] without outvoting agreement
 * on the user's question, and `keywordWeight = 0.0` silences it.
 */
class FuseStageKeywordLegSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private def mem(id: String): ContextMemory = ContextMemory(
    fact = s"fact $id",
    label = id,
    summary = s"fact $id",
    source = MemorySource.Explicit,
    spaceId = TestSpace,
    _id = ContextMemory.id(id)
  )

  private val questionHit = mem("question-hit")
  private val mixedHit = mem("mixed-hit")
  private val keywordOnly = mem("keyword-only")

  private def ctx: MemoryRetrievalContext = MemoryRetrievalContext(
    sigil = TestSigil,
    conversationId = Conversation.id("fuse-keyword"),
    query = "unused",
    spaces = Set(TestSpace),
    currentMode = None,
    now = Timestamp(),
    limit = 5,
    candidatePool = 20
  )

  private def state: MemoryRetrievalState = MemoryRetrievalState(
    lexical = Vector(questionHit, mixedHit),
    vectorHits = Vector(questionHit),
    keywordHits = Vector(keywordOnly, mixedHit)
  )

  private def stage(keywordWeight: Double): FuseStage =
    FuseStage(keywordWeight = keywordWeight, recencyWeight = 0.0, reinforcementWeight = 0.0)

  "the fuse stage" should {
    "rank question-leg agreement above keyword-supported and keyword-only hits" in {
      stage(keywordWeight = 1.0).run(state, ctx).map { fused =>
        fused.ranked.map(_._id.value) shouldBe Vector("question-hit", "mixed-hit", "keyword-only")
      }
    }

    "keep a keyword-only hit reachable through the context leg" in {
      stage(keywordWeight = 1.0).run(state.copy(lexical = Vector(mixedHit), vectorHits = Vector.empty), ctx).map { fused =>
        fused.ranked.map(_._id.value) should contain ("keyword-only")
      }
    }

    "silence the context leg at keywordWeight = 0" in {
      stage(keywordWeight = 0.0).run(state, ctx).map { fused =>
        // The keyword-only memory scores zero — it can only trail the
        // question-backed entries.
        fused.ranked.map(_._id.value).take(2) shouldBe Vector("question-hit", "mixed-hit")
        fused.ranked.map(_._id.value).last shouldBe "keyword-only"
      }
    }
  }
}
