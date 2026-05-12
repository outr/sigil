package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.ContextFrame
import sigil.information.{Information, InformationSummary}

/**
 * Default [[BlockExtractor]] impl. Scans Text and ToolResult frames;
 * anything whose content length is at least [[minChars]] gets pulled
 * into an [[Information]] record (constructed via the app-supplied
 * [[toInformation]] factory), persisted via
 * [[sigil.Sigil.putInformation]], and replaced in the frame vector
 * with a placeholder reference.
 *
 * Apps must override [[sigil.Sigil.putInformation]] to a functional
 * implementation — without it, the catalog references won't resolve
 * through [[sigil.tool.util.LookupTool]].
 *
 * Knobs:
 *   - [[minChars]]: size threshold in characters (default 2000 ≈ 500
 *     tokens). Frames shorter than this are left alone.
 *   - [[extractText]] / [[extractToolResult]]: limit extraction to
 *     either kind of frame.
 *   - [[placeholder]]: renders the in-frame reference text from the
 *     newly-minted Information id and catalog summary.
 *   - [[summaryOf]]: produces the catalog's 1-2 line teaser. Default
 *     uses the first non-empty line, capped at 140 chars.
 */
case class StandardBlockExtractor(toInformation: (String, Id[Information]) => Information,
                                  minChars: Int = 2000,
                                  extractText: Boolean = true,
                                  extractToolResult: Boolean = true,
                                  placeholder: (Id[Information], String) => String =
                                    StandardBlockExtractor.DefaultPlaceholder,
                                  summaryOf: String => String = StandardBlockExtractor.DefaultSummary,
                                  /** Emit a progress callback every N frames inspected.
                                    * Default 500 keeps the activity-bar pulse cheap on small
                                    * runs but visible on imports. Apps with very wide UIs
                                    * tighten; apps with no progress surface keep the
                                    * default — the callback is a no-op by default. */
                                  progressEvery: Int = 500) extends BlockExtractor {

  override def extract(sigil: Sigil,
                       frames: Vector[ContextFrame],
                       progress: BlockExtractor.ProgressCallback): Task[BlockExtractionResult] = {
    val total = frames.size
    // Walk the frame vector once, building the eventual outcome
    // vector + a flat batch of new Information records to persist in
    // one shot. No I/O happens during the walk — the bulk write is
    // amortised at the end via `Sigil.putInformations`. Progress
    // pulses every `progressEvery` frames so the curator's
    // activity-bar label reflects forward motion.
    val outcomes = new Array[ContextFrame](total)
    val summariesBuilder = Vector.newBuilder[InformationSummary]
    val infosBuilder     = Vector.newBuilder[Information]

    def walk(remaining: List[(ContextFrame, Int)]): Task[Unit] = remaining match {
      case Nil => Task.unit
      case (frame, idx) :: rest =>
        val (resultFrame, summary, info) = frame match {
          case t: ContextFrame.Text if extractText && t.content.length >= minChars =>
            val (f, s, i) = buildExtraction(t.content, replacement => t.copy(content = replacement))
            (f, Some(s), Some(i))
          case tr: ContextFrame.ToolResult if extractToolResult && tr.content.length >= minChars =>
            val (f, s, i) = buildExtraction(tr.content, replacement => tr.copy(content = replacement))
            (f, Some(s), Some(i))
          case other =>
            (other, None, None)
        }
        outcomes(idx) = resultFrame
        summary.foreach(summariesBuilder += _)
        info.foreach(infosBuilder += _)
        val nextIdx = idx + 1
        val tick: Task[Unit] =
          if (progressEvery > 0 && nextIdx % progressEvery == 0) progress(nextIdx, total)
          else Task.unit
        tick.flatMap(_ => walk(rest))
    }

    for {
      _ <- walk(frames.iterator.zipWithIndex.toList)
      infos = infosBuilder.result()
      // One bulk write at the end. Apps with a transactional store
      // override `putInformations` to a single multi-upsert; the
      // default falls back to `N` calls to `putInformation` for
      // backwards compatibility.
      _ <- if (infos.isEmpty) Task.unit else sigil.putInformations(infos)
      _ <- if (progressEvery > 0 && total > 0 && total % progressEvery != 0) progress(total, total) else Task.unit
    } yield BlockExtractionResult(outcomes.toVector, summariesBuilder.result())
  }

  /** Allocate an Information id, build the record via the factory,
    * and return the replaced frame + the catalog summary + the
    * record to be persisted at the end of the pass. No I/O here. */
  private def buildExtraction(content: String,
                              replace: String => ContextFrame): (ContextFrame, InformationSummary, Information) = {
    val newId   = Id[Information]()
    val info    = toInformation(content, newId)
    val summary = summaryOf(content)
    val refText = placeholder(newId, summary)
    (
      replace(refText),
      InformationSummary(
        id              = newId,
        informationType = info.informationType,
        summary         = summary
      ),
      info
    )
  }
}

object StandardBlockExtractor {
  val DefaultPlaceholder: (Id[Information], String) => String =
    (id, summary) => s"(large content stored as Information[${id.value}]. Summary: $summary. Use `lookup(capabilityType=\"Information\", name=\"${id.value}\")` to retrieve full content.)"

  val DefaultSummary: String => String = content => {
    val firstLine = content.linesIterator.find(_.trim.nonEmpty).getOrElse("").trim
    if (firstLine.length > 140) firstLine.take(137) + "..." else firstLine
  }
}
