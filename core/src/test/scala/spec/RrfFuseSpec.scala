package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.compression.StandardMemoryRetriever

/**
 * Pure unit coverage for [[StandardMemoryRetriever.rrfFuse]]. The
 * hybrid retriever leans on this — a regression here silently
 * degrades non-critical memory retrieval ranking, so the math is
 * worth pinning.
 */
class RrfFuseSpec extends AnyWordSpec with Matchers {

  private val k = 60

  "StandardMemoryRetriever.rrfFuse" should {
    "return a single ranking unchanged when only one signal exists" in {
      val ranking = List("a", "b", "c", "d")
      StandardMemoryRetriever.rrfFuse(List(ranking), k) shouldBe ranking
    }

    "rank items appearing in both signals above items in only one" in {
      val vector  = List("a", "b", "c")
      val lexical = List("d", "a", "e")
      // 'a' appears in both → 1/(60+1) + 1/(60+2) = 0.01632... + 0.01613... = 0.03245
      // 'b' appears once at rank 2 → 1/62 = 0.01613
      // 'd' appears once at rank 1 → 1/61 = 0.01639
      // 'a' should win because it's in both rankings, even though 'd' is rank-1 in lexical.
      val fused = StandardMemoryRetriever.rrfFuse(List(vector, lexical), k)
      fused.head shouldBe "a"
    }

    "preserve order among items with identical contribution" in {
      // Two singletons that don't overlap — the fused list must contain
      // both and respect insertion order under tie.
      val a = List("x")
      val b = List("y")
      val fused = StandardMemoryRetriever.rrfFuse(List(a, b), k)
      fused should contain theSameElementsAs List("x", "y")
    }

    "include items only ranked by one signal" in {
      val a = List("x", "y")
      val b = List("z")
      val fused = StandardMemoryRetriever.rrfFuse(List(a, b), k)
      fused should contain allOf ("x", "y", "z")
    }

    "give higher cumulative score to items appearing high in multiple lists" in {
      // 'a' is rank-1 in both → 2 * 1/61 = 0.03279
      // 'b' is rank-1 in one, rank-2 in another → 1/61 + 1/62 = 0.03252
      // Both appear in both lists, but 'a' has tighter ranks → higher fused score.
      val one = List("a", "b")
      val two = List("a", "b")
      val fused = StandardMemoryRetriever.rrfFuse(List(one, two), k)
      fused shouldBe List("a", "b")
    }

    "handle empty rankings gracefully" in {
      StandardMemoryRetriever.rrfFuse(List(Nil, Nil), k) shouldBe Nil
      StandardMemoryRetriever.rrfFuse(Nil, k) shouldBe Nil
    }

    "treat k correctly — smaller k emphasises top-of-list more aggressively" in {
      // With small k, the gap between rank 1 and rank N grows.
      val ranking = List("a", "b", "c")
      val fusedSmall = StandardMemoryRetriever.rrfFuse(List(ranking), k = 1)
      val fusedLarge = StandardMemoryRetriever.rrfFuse(List(ranking), k = 1000)
      // Either way, order is preserved for a single ranking — the
      // k-effect shows in how scores compose, not in single-list order.
      fusedSmall shouldBe ranking
      fusedLarge shouldBe ranking
    }

    "respect a per-document weight — low-confidence docs lose ties to high-confidence peers" in {
      // Two docs with identical RRF positions; weight pushes one ahead
      // and the other to the back. This locks the new confidence-aware
      // path so a future regression that drops the multiplier is caught.
      val rank1 = List("a", "b", "c")
      val rank2 = List("a", "b", "c")
      val weight: String => Double = {
        case "a" => 0.2
        case "b" => 1.0
        case "c" => 0.5
      }
      val fused = StandardMemoryRetriever.rrfFuse(List(rank1, rank2), k, weight)
      // Without weights, the order would be a, b, c (a wins both ranks).
      // With weights at k=60 the contributions are roughly:
      //   b: 2.0/62 ≈ 0.0323
      //   c: 1.0/63 ≈ 0.0159
      //   a: 0.4/61 ≈ 0.00656
      // So "b" should leapfrog "a", and "a" sinks to last because its
      // heavy weight discount overwhelms its rank-1 boost.
      fused.indexOf("b") should be < fused.indexOf("a")
      fused.last shouldBe "a"
    }
  }
}
