package sigil.conversation.compression.retrieval

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Unit pins for the BM25 legs' query tokenizer (bug #415). Lives in
 * the retrieval package so the package-private `tokensOf` is reachable
 * without widening it for tests; the end-to-end consequences are
 * pinned separately by `spec.RecallQueryTokenizationSpec`.
 *
 * The query must be tokenized the way indexed content is: punctuation
 * split off (or the one discriminative term arrives as `"tobacco?"`
 * and matches no indexed term) and stopwords dropped (or `where` /
 * `do` / `you` / `your` OR-match swathes of unrelated facts and, at
 * `lexicalWeight = 2.0`, outvote a correct vector ranking).
 */
class RecallTokenizerSpec extends AnyWordSpec with Matchers {

  private def tokens(text: String): List[String] = RecallStage().tokensOf(text)

  "tokensOf" should {
    "reduce a natural-language question to its discriminative terms" in {
      tokens("Where do you keep your tobacco?") shouldBe List("keep", "tobacco")
    }

    "strip a trailing question mark from the discriminative term" in {
      tokens("tobacco?") shouldBe List("tobacco")
    }

    "split internal punctuation and lowercase" in {
      tokens("Mrs. Hudson's lodgers — WHO were they?") should contain allOf ("mrs", "hudson", "lodgers")
    }

    "drop every stopword when discriminative terms remain" in {
      val stopwordsInQuery = Set("where", "do", "you", "your", "how", "what", "the", "is", "a")
      tokens("How is the tobacco stored, and where do you keep your slipper?")
        .foreach(t => stopwordsInQuery should not contain t)
      succeed
    }

    "fall back to raw terms for a query of nothing but stopwords" in {
      // A leg matching common words is weak; a leg matching nothing
      // gives up recall the fusion has no other source for.
      val t = tokens("What do you do?")
      t should not be empty
      t should contain("what")
    }

    "deduplicate repeated terms" in {
      tokens("tobacco tobacco slipper tobacco") shouldBe List("tobacco", "slipper")
    }

    "cap the term list so the clause count stays bounded" in {
      val many = (1 to (RecallStage.MaxQueryTokens + 20)).map(i => s"term$i").mkString(" ")
      val t = tokens(many)
      t.size shouldBe RecallStage.MaxQueryTokens
      t.distinct shouldBe t
    }

    "cap the fallback path too" in {
      // Pure stopwords, more than the cap — the fallback must respect
      // the same clause bound the filtered path does.
      val many = (1 to (RecallStage.MaxQueryTokens + 10)).map(_ => "you do what where how").mkString(" ")
      tokens(many).size should be <= RecallStage.MaxQueryTokens
    }

    "return nothing for blank or punctuation-only input" in {
      tokens("") shouldBe empty
      tokens("   ") shouldBe empty
      tokens("?!—...") shouldBe empty
    }
  }
}
