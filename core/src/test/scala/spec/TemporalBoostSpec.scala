package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.vector.{TemporalBoost, VectorSearchResult}

/**
 * Coverage for [[TemporalBoost]]: candidates close in time to the
 * query reference outrank further-away candidates of equal base
 * score; candidates without a timestamp payload degrade gracefully.
 */
class TemporalBoostSpec extends AnyWordSpec with Matchers {

  private val now: Long = 1_700_000_000_000L

  private def candidate(id: String, score: Double, timestampOffsetMs: Long = 0L, hasTimestamp: Boolean = true): VectorSearchResult =
    VectorSearchResult(
      id = id,
      score = score,
      payload = if (hasTimestamp) Map(TemporalBoost.TimestampKey -> (now + timestampOffsetMs).toString) else Map.empty
    )

  "TemporalBoost.rerank" should {
    "lift the closer-in-time candidate above a further-away one with equal base score" in {
      val boost = TemporalBoost(halfLifeMs = TemporalBoost.HalfLife.OneDay, temporalWeight = 0.5)
      val close = candidate("close", 0.8, timestampOffsetMs = 0L)
      val far = candidate("far", 0.8, timestampOffsetMs = TemporalBoost.HalfLife.OneDay * 10)
      val out = boost.rerank(List(far, close), referenceTimeMs = now)
      out.head.id shouldBe "close"
    }

    "preserve order when no candidates have timestamps" in {
      val boost = TemporalBoost(halfLifeMs = TemporalBoost.HalfLife.OneDay)
      val a = candidate("a", 0.9, hasTimestamp = false)
      val b = candidate("b", 0.7, hasTimestamp = false)
      val out = boost.rerank(List(a, b), referenceTimeMs = now)
      out.map(_.id) shouldBe List("a", "b")
    }

    "apply half-decay at exactly one half-life away" in {
      val boost = TemporalBoost(halfLifeMs = 1000L, temporalWeight = 1.0)
      val atRef = candidate("atRef", 0.0, timestampOffsetMs = 0L)
      val oneHalfLife = candidate("oneHalfLife", 0.0, timestampOffsetMs = 1000L)
      val out = boost.rerank(List(atRef, oneHalfLife), referenceTimeMs = now)
      out.find(_.id == "atRef").get.score shouldBe 1.0 +- 1e-9
      out.find(_.id == "oneHalfLife").get.score shouldBe 0.5 +- 1e-9
    }

    "handle empty input" in {
      TemporalBoost(halfLifeMs = 1000L).rerank(Nil, now) shouldBe Nil
    }

    "leave candidates without a timestamp below candidates with a close timestamp" in {
      val boost = TemporalBoost(halfLifeMs = TemporalBoost.HalfLife.OneDay, temporalWeight = 0.5)
      val withTs = candidate("withTs", 0.5, timestampOffsetMs = 0L)
      val noTs = candidate("noTs", 0.5, hasTimestamp = false)
      val out = boost.rerank(List(noTs, withTs), referenceTimeMs = now)
      out.head.id shouldBe "withTs"
    }
  }
}
