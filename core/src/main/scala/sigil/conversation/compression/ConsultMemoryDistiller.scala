package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.ContextMemory
import sigil.db.Model
import sigil.tool.consult.{ConsultTool, DistillMemoryInput, DistillMemoryTool}

/**
 * [[MemoryDistiller]] backed by a one-shot LLM consult
 * ([[DistillMemoryTool]]) against `modelId`. Ingest runs at build
 * time, so `modelId` may be a strong model without affecting the
 * runtime model's cost.
 *
 * Skips (returns `None`, memory stored as-is):
 *   - facts shorter than [[minFactChars]] — a short fact IS its own
 *     summary; a consult would spend a call to restate it;
 *   - memories whose caller already authored a `summary` distinct
 *     from the `fact` — explicit authorship is respected.
 *
 * A consult failure or empty reply is logged by the caller and the
 * memory persists undistilled — ingest never fails on a distillation
 * hiccup.
 */
case class ConsultMemoryDistiller(modelId: Id[Model],
                                  minFactChars: Int = 400) extends MemoryDistiller {

  override def distill(sigil: Sigil, memory: ContextMemory): Task[Option[MemoryDistillation]] = {
    val fact = memory.fact.trim
    val summary = memory.summary.trim
    if (fact.length < minFactChars) Task.pure(None)
    else if (summary.nonEmpty && summary != fact) Task.pure(None)
    else {
      val labelLine = if (memory.label.trim.nonEmpty) s"Label: ${memory.label.trim}\n" else ""
      ConsultTool.invoke[DistillMemoryInput](
        sigil = sigil,
        modelId = modelId,
        chain = memory.createdBy.toList,
        systemPrompt = ConsultMemoryDistiller.SystemPrompt,
        userPrompt = s"${labelLine}Fact:\n$fact",
        tool = DistillMemoryTool,
        generationSettings = DistillMemoryTool.consultSettings
      ).map(_.flatMap { input =>
        val distilledSummary = input.summary.trim
        if (distilledSummary.isEmpty) None
        else Some(MemoryDistillation(
          summary = distilledSummary,
          embeddingText = input.retrievalText.map(_.trim).filter(_.nonEmpty)
        ))
      })
    }
  }
}

object ConsultMemoryDistiller {
  val SystemPrompt: String =
    """You distill stored memories via the `distill_memory` tool. Read the fact and return a
      |one-line summary a reader can scan, plus (when the fact is long or context-dependent)
      |a self-contained retrieval rewrite. Preserve names, numbers, and identifiers exactly;
      |never invent information that is not in the fact.""".stripMargin
}
