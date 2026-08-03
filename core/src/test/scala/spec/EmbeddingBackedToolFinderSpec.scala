package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.provider.ConversationMode
import sigil.tool.{DiscoveryRequest, EmbeddingBackedToolFinder, InMemoryToolFinder, Tool, ToolFinder, ToolName}
import sigil.vector.InMemoryVectorIndex

/**
 * [[EmbeddingBackedToolFinder]] ranks discovery by semantic similarity
 * instead of the lexical scoring [[sigil.tool.DbToolFinder]] uses, and
 * degrades to its fallback whenever the vector path can't help — no
 * embeddings wired, an empty index, or an empty query.
 */
class EmbeddingBackedToolFinderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val roster: List[Tool] = TestSigil.resolvedStaticTools
  private val fallback: ToolFinder = InMemoryToolFinder(roster)

  private def request(keywords: String): DiscoveryRequest = DiscoveryRequest(
    keywords = keywords,
    chain = List(TestUser),
    mode = ConversationMode,
    callerSpaces = Set(sigil.GlobalSpace)
  )

  "EmbeddingBackedToolFinder" should {

    "index every catalog tool and return vector-ranked results" in {
      TestSigil.reset()
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)
      val finder = new EmbeddingBackedToolFinder(TestSigil, Nil, fallback)
      for {
        indexed <- finder.indexAll
        results <- finder(request("stop what you are doing"))
      } yield {
        indexed should be > 0
        results should not be empty
        // Every hit resolved through the fallback's byName, so each is a
        // real roster tool rather than a dangling vector payload.
        results.map(_.name).toSet.subsetOf(roster.map(_.name).toSet) shouldBe true
      }
    }

    "fall back when the vector index has no tool entries" in {
      TestSigil.reset()
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)
      val finder = new EmbeddingBackedToolFinder(TestSigil, Nil, fallback)
      for {
        vectorResults <- finder(request("respond to the user"))
        fallbackResults <- fallback(request("respond to the user"))
      } yield vectorResults.map(_.name) shouldBe fallbackResults.map(_.name)
    }

    "fall back when the query is empty" in {
      TestSigil.reset()
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)
      val finder = new EmbeddingBackedToolFinder(TestSigil, Nil, fallback)
      finder(request("   ")).flatMap { vectorResults =>
        fallback(request("   ")).map(f => vectorResults.map(_.name) shouldBe f.map(_.name))
      }
    }

    "delegate byName to the fallback" in {
      TestSigil.reset()
      val finder = new EmbeddingBackedToolFinder(TestSigil, Nil, fallback)
      finder.byName(ToolName("respond")).map(_.map(_.name.value) shouldBe Some("respond"))
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
