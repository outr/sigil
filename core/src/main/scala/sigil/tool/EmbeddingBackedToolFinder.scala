package sigil.tool

import fabric.rw.RW
import rapid.Task
import sigil.Sigil
import sigil.embedding.EmbeddingProvider
import sigil.vector.{NoOpVectorIndex, VectorIndex, VectorPoint}

/**
 * Embedding-backed [[ToolFinder]]. Composes an [[EmbeddingProvider]]
 * over the [[VectorIndex]] of indexed tool descriptions, ranking
 * candidates by semantic similarity to the [[DiscoveryRequest]]'s
 * keyword query rather than the lexical scoring [[DbToolFinder]] uses.
 *
 * Falls back to a [[DbToolFinder]] when:
 *   - the embedding provider has `dimensions == 0` (no embeddings
 *     wired)
 *   - the underlying [[VectorIndex]] has no tool entries
 *   - the query is empty
 *
 * Apps that want pure semantic-only behavior should pass a no-op
 * fallback; the default fallback keeps existing behavior intact when
 * the vector path can't help.
 *
 * Index population: apps call [[indexTool]] / [[indexAll]] to upsert
 * tool description embeddings into the vector index under
 * `kind=tool`. Sigil's `StaticToolSyncUpgrade` is the natural place to
 * call `indexAll` at app startup; runtime tool additions (marketplace
 * installs, MCP discovery) call `indexTool`.
 */
final class EmbeddingBackedToolFinder(sigil: Sigil,
                                      override val toolInputRWs: List[RW[? <: ToolInput]],
                                      fallback: ToolFinder)
  extends ToolFinder {

  private val embedding: EmbeddingProvider = sigil.embeddingProvider
  private val vectors: VectorIndex = sigil.vectorIndex
  private val vectorWired: Boolean =
    embedding.dimensions > 0 && (vectors ne NoOpVectorIndex)

  /** Upsert this tool's embedding into the vector index. Apps call
    * this when adding tools at runtime (marketplace install, MCP
    * discovery). */
  def indexTool(tool: Tool): Task[Unit] =
    if (!vectorWired) Task.unit
    else embedding.embed(tool.description).flatMap { vec =>
      vectors.upsert(VectorPoint(
        id = s"tool:${tool.schema.name.value}",
        vector = vec,
        payload = Map(
          "kind"     -> "tool",
          "toolName" -> tool.schema.name.value
        )
      ))
    }

  /** Bulk-index every tool currently in [[Sigil]]'s tool collection.
    * Idempotent — safe to run on every startup. */
  def indexAll: Task[Int] =
    if (!vectorWired) Task.pure(0)
    else sigil.withDB(_.tools.transaction(_.list)).flatMap { tools =>
      Task.sequence(tools.map(indexTool)).map(_ => tools.size)
    }

  override def apply(request: DiscoveryRequest): Task[List[Tool]] = {
    val q = request.keywords.trim
    if (!vectorWired || q.isEmpty) fallback(request)
    else embedding.embed(q).flatMap { vec =>
      vectors.search(vec, limit = 50, filter = Map("kind" -> "tool")).flatMap { hits =>
        if (hits.isEmpty) fallback(request)
        else {
          val names = hits.flatMap(_.payload.get("toolName"))
          // Hydrate hits via the fallback's byName, then post-filter by
          // mode/space affinity so we keep DiscoveryFilter's existing
          // semantics. Vector ranking decides the order.
          Task.sequence(names.map(n => fallback.byName(ToolName(n))))
            .map { resolved =>
              resolved.flatten
                .filter(t => DiscoveryFilter.matches(t, request))
            }
        }
      }
    }
  }

  override def byName(name: ToolName): Task[Option[Tool]] = fallback.byName(name)
}

object EmbeddingBackedToolFinder {

  /** Convenience: wrap a [[DbToolFinder]] with embedding-backed
    * discovery when vectors are wired. */
  def overDb(sigil: Sigil, toolInputRWs: List[RW[? <: ToolInput]]): EmbeddingBackedToolFinder =
    new EmbeddingBackedToolFinder(
      sigil = sigil,
      toolInputRWs = toolInputRWs,
      fallback = DbToolFinder(sigil, toolInputRWs)
    )
}
