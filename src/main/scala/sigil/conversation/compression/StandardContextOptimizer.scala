package sigil.conversation.compression

import sigil.conversation.ContextFrame

/**
 * Default [[ContextOptimizer]] — strips empty/whitespace-only Text
 * frames and dedups consecutive identical Text frames from the same
 * participant. Conservative by design; the heavy lifting (LLM
 * summarization) is [[ContextCompressor]]'s job.
 */
class StandardContextOptimizer extends ContextOptimizer {
  override def optimize(frames: Vector[ContextFrame]): Vector[ContextFrame] = {
    val pruned = frames.filter {
      case ContextFrame.Text(content, _, _) => content.trim.nonEmpty
      case _                                => true
    }
    dedupConsecutiveText(pruned)
  }

  private def dedupConsecutiveText(frames: Vector[ContextFrame]): Vector[ContextFrame] = {
    val out = Vector.newBuilder[ContextFrame]
    var prev: Option[ContextFrame.Text] = None
    frames.foreach {
      case t: ContextFrame.Text =>
        val isDup = prev.exists(p => p.content == t.content && p.participantId == t.participantId)
        if (!isDup) {
          out += t
          prev = Some(t)
        }
      case other =>
        out += other
        prev = None
    }
    out.result()
  }
}
