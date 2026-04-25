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
 * through [[sigil.tool.util.LookupInformationTool]].
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
                                  summaryOf: String => String = StandardBlockExtractor.DefaultSummary) extends BlockExtractor {

  override def extract(sigil: Sigil, frames: Vector[ContextFrame]): Task[BlockExtractionResult] = {
    val pending = Vector.newBuilder[Task[FrameOutcome]]
    frames.foreach {
      case t: ContextFrame.Text if extractText && t.content.length >= minChars =>
        pending += extractOne(sigil, t.content, replacement => t.copy(content = replacement))

      case tr: ContextFrame.ToolResult if extractToolResult && tr.content.length >= minChars =>
        pending += extractOne(sigil, tr.content, replacement => tr.copy(content = replacement))

      case other =>
        pending += Task.pure(FrameOutcome(other, None))
    }

    Task.sequence(pending.result().toList).map { outcomes =>
      val keptFrames = Vector.newBuilder[ContextFrame]
      val newInfo = Vector.newBuilder[InformationSummary]
      outcomes.foreach { out =>
        keptFrames += out.frame
        out.summary.foreach(newInfo += _)
      }
      BlockExtractionResult(keptFrames.result(), newInfo.result())
    }
  }

  /** Allocate an Information id, build the record via the factory,
    * persist it, and return a FrameOutcome carrying the replaced
    * frame + the catalog summary. */
  private def extractOne(sigil: Sigil,
                         content: String,
                         replace: String => ContextFrame): Task[FrameOutcome] = {
    val newId = Id[Information]()
    val info = toInformation(content, newId)
    val summary = summaryOf(content)
    val refText = placeholder(newId, summary)
    sigil.putInformation(info).map { _ =>
      FrameOutcome(
        frame = replace(refText),
        summary = Some(InformationSummary(
          id = newId,
          informationType = info.informationType,
          summary = summary
        ))
      )
    }
  }

  private case class FrameOutcome(frame: ContextFrame, summary: Option[InformationSummary])
}

object StandardBlockExtractor {
  val DefaultPlaceholder: (Id[Information], String) => String =
    (id, summary) => s"(large content stored as Information[${id.value}]. Summary: $summary. Use `lookup_information` to retrieve full content.)"

  val DefaultSummary: String => String = content => {
    val firstLine = content.linesIterator.find(_.trim.nonEmpty).getOrElse("").trim
    if (firstLine.length > 140) firstLine.take(137) + "..." else firstLine
  }
}
