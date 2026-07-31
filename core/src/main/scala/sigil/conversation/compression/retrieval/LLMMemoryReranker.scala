package sigil.conversation.compression.retrieval

import rapid.Task
import sigil.Sigil
import sigil.conversation.ContextMemory
import sigil.vector.{HybridSearch, LLMReranker, VectorSearchResult}

/**
 * [[MemoryReranker]] backed by the existing [[LLMReranker]] consult.
 * Each memory is presented to the model as its rendered retrieval text
 * (`summary`, falling back to `fact` — the same policy the provider's
 * memory renderer applies); the model's id ordering is mapped back to
 * records. Candidates the model omits keep their original relative
 * order after the ranked ones; a failed consult leaves the list
 * unchanged (the wrapped reranker already degrades that way).
 */
case class LLMMemoryReranker(llm: LLMReranker) extends MemoryReranker {

  override def rerank(sigil: Sigil, query: String, memories: Vector[ContextMemory]): Task[Vector[ContextMemory]] =
    if (memories.size <= 1) Task.pure(memories)
    else {
      val candidates = memories.iterator.zipWithIndex.map { case (m, idx) =>
        VectorSearchResult(
          id = m._id.value,
          score = 1.0 / (idx + 1),
          payload = Map(HybridSearch.TextKey -> renderText(m))
        )
      }.toList
      val byId = memories.iterator.map(m => m._id.value -> m).toMap
      llm.rerank(sigil, query, candidates).map(_.iterator.flatMap(r => byId.get(r.id)).toVector)
    }

  private def renderText(m: ContextMemory): String =
    if (m.summary.trim.nonEmpty) m.summary else m.fact
}
