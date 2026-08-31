package spec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.SpaceId
import sigil.conversation.{ContextMemory, MemorySource}
import sigil.embedding.EmbeddingProvider
import sigil.vector.InMemoryVectorIndex

/**
 * Bug #416 — `searchMemories` must treat an empty `spaces` set as an
 * empty SCOPE (nothing), on both the vector and the non-vector branch,
 * exactly like `findMemories`. Under vector search it used to mean
 * "no filter": every tenant's memories, ranked — silent in the one
 * environment (production, vector wired) where it mattered, and
 * invisible in dev/test (no vector index) where the same call was
 * already empty.
 */
class SearchMemoriesScopeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Embeds every text to the same direction so any query "matches"
   * any stored memory — the leak, if present, shows up on any query.
   */
  private object UniformEmbedding extends EmbeddingProvider {
    override val id: String = "scope-uniform"
    override val dimensions: Int = 2
    override def embed(text: String): Task[Vector[Double]] = Task.pure(Vector(1.0, 0.0))
    override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
      Task.pure(texts.map(_ => Vector(1.0, 0.0)))
  }

  private def memory(fact: String, space: SpaceId): ContextMemory =
    ContextMemory(
      fact = fact,
      label = fact.take(16),
      summary = fact,
      source = MemorySource.Explicit,
      spaceId = space)

  override protected def afterAll(): Unit = {
    TestSigil.reset()
    super.afterAll()
  }

  "searchMemories with vector search wired" should {
    "seed two tenants' memories" in {
      TestSigil.setEmbeddingProvider(UniformEmbedding)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)
      TestSigil.persistMemories(List(
        memory("Tenant A keeps its deploy key in the vault.", TestSpace),
        memory("Tenant B's deploy key lives in the ops drawer.", MemoryTestSpace)
      )).map(_ should have size 2)
    }

    "return nothing for an empty scope even though every memory matches the query" in
      TestSigil.searchMemories("deploy key", Set.empty).map(_ shouldBe empty)

    "still return the in-scope tenant's memories for a concrete scope" in
      TestSigil.searchMemories("deploy key", Set(TestSpace)).map { hits =>
        hits.map(_.spaceId) should contain only TestSpace
        hits should have size 1
      }

    "agree with findMemories on the empty scope" in {
      for {
        searched <- TestSigil.searchMemories("deploy key", Set.empty)
        listed <- TestSigil.findMemories(Set.empty)
      } yield {
        searched shouldBe empty
        listed shouldBe empty
      }
    }
  }

  "searchMemories without vector search" should {
    "return nothing for an empty scope" in {
      TestSigil.reset()
      TestSigil.searchMemories("deploy key", Set.empty).map(_ shouldBe empty)
    }

    "still return the in-scope tenant's memories for a concrete scope" in
      TestSigil.searchMemories("deploy key", Set(MemoryTestSpace)).map { hits =>
        hits.map(_.spaceId) should contain only MemoryTestSpace
        hits should have size 1
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
