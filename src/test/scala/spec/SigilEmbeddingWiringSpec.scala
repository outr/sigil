package spec

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import profig.Profig
import rapid.{AsyncTaskSpec, Task}
import sigil.{Sigil, SignalBroadcaster}
import sigil.conversation.{ContextMemory, ContextSummary, Conversation, MemorySource, MemorySpaceId}
import sigil.db.Model
import sigil.embedding.EmbeddingProvider
import sigil.participant.ParticipantId
import sigil.provider.Provider
import sigil.tool.{InMemoryToolFinder, ToolFinder}
import sigil.tool.core.CoreTools
import sigil.vector.{InMemoryVectorIndex, VectorIndex}

/**
 * Verifies Sigil's vector wiring: when an [[EmbeddingProvider]] and a
 * non-NoOp [[VectorIndex]] are supplied, [[Sigil.persistMemory]] and
 * [[Sigil.persistSummary]] auto-index their text, and the
 * [[Sigil.searchMemories]] API can retrieve records via semantic
 * similarity through the vector index.
 *
 * Uses a deterministic [[HashEmbeddingProvider]] so results are
 * reproducible — the actual embedding quality isn't what's under test;
 * the plumbing is.
 */
class SigilEmbeddingWiringSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  WiringSigil.initFor("SigilEmbeddingWiringSpec")

  private val space: MemorySpaceId = WiringSpace
  private val convId = Conversation.id("wiring-conv")

  "Sigil with vector wiring" should {
    "auto-index a persisted ContextMemory and retrieve it via searchMemories" in {
      val mem = ContextMemory(
        fact = "The capital of France is Paris.",
        source = MemorySource.Explicit,
        spaceId = space
      )
      for {
        _ <- WiringSigil.persistMemory(mem)
        hits <- WiringSigil.searchMemories("capital France", Set(space), limit = 5)
      } yield {
        hits.map(_._id.value) should contain(mem._id.value)
      }
    }

    "auto-index a persisted ContextSummary (side-effect on the vector index)" in {
      val summary = ContextSummary(
        text = "Earlier in the conversation we discussed French geography.",
        conversationId = convId,
        tokenEstimate = 12
      )
      for {
        _ <- WiringSigil.persistSummary(summary)
        hits <- WiringSigil.vectorIndex.search(
          WiringSigil.embeddingProvider.embed("French geography").sync(),
          limit = 5,
          filter = Map("kind" -> "summary")
        )
      } yield hits.map(_.id) should contain(summary._id.value)
    }
  }
}

/** A test-only [[EmbeddingProvider]]: deterministic, no external I/O.
  * Each token contributes to a fixed-dim vector via a stable hash — good
  * enough to verify plumbing without pulling in a real embedding API. */
private object HashEmbeddingProvider extends EmbeddingProvider {
  override def dimensions: Int = 32

  override def embed(text: String): Task[Vector[Double]] = Task {
    val buf = Array.fill(dimensions)(0.0)
    text.toLowerCase.split("\\W+").filter(_.nonEmpty).foreach { tok =>
      val h = tok.hashCode
      val i = math.floorMod(h, dimensions)
      buf(i) += 1.0
    }
    val norm = math.sqrt(buf.map(x => x * x).sum)
    if (norm == 0.0) buf.toVector else buf.map(_ / norm).toVector
  }

  override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
    Task.sequence(texts.map(embed))
}

/** Synthetic memory space for the wiring spec. */
case object WiringSpace extends MemorySpaceId {
  override val value: String = "wiring-space"
}

/**
 * Sigil variant with [[HashEmbeddingProvider]] + an in-memory vector
 * index wired. Lives in its own test — it opens its own RocksDB path
 * via `initFor` so it doesn't collide with `TestSigil`.
 */
object WiringSigil extends Sigil {
  override def testMode: Boolean = true

  override val findTools: ToolFinder = InMemoryToolFinder(CoreTools.all.toList)

  override val embeddingProvider: EmbeddingProvider = HashEmbeddingProvider
  override val vectorIndex: VectorIndex = new InMemoryVectorIndex

  override protected def memorySpaceIds: List[RW[? <: MemorySpaceId]] =
    List(RW.static(WiringSpace))

  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(TestUser), RW.static(TestAgent))

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    Task.error(new UnsupportedOperationException("WiringSigil: no provider configured"))

  override def broadcaster: SignalBroadcaster = SignalBroadcaster.NoOp

  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = java.nio.file.Path.of("db", "test", name)
    deleteRecursive(dbPath)
    Profig.merge(obj("sigil" -> obj("dbPath" -> str(dbPath.toString))))
    instance.sync()
    ()
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit = {
    if (java.nio.file.Files.exists(path)) {
      val stream = java.nio.file.Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        stream.iterator().asScala.toList.reverse
          .foreach(p => java.nio.file.Files.deleteIfExists(p))
      } finally stream.close()
    }
  }
}
