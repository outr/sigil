package sigil.provider

import sigil.tokenize.{HeuristicTokenizer, Tokenizer}

/**
 * What a [[ContextSection]]'s renderer produced, in the shape the
 * framework needs to enforce a per-section token budget.
 *
 * The distinction is not cosmetic: an entry-shaped section is a header
 * over independent lines, any suffix of which can be dropped and still
 * read correctly, so a budget can be honoured by trimming. A blob is
 * prose — instructions, a role description, a mode block — and cutting
 * it mid-sentence corrupts the very thing it was there to say, so blobs
 * ignore budgets entirely. A section that cannot be trimmed safely is
 * shed whole by the curator's cascade instead.
 */
enum SectionBody {

  /** Prose rendered as one unit. Never budget-trimmed. */
  case Blob(text: String)

  /** A header over independently droppable lines, plus an optional
    * footer. `lines` is in priority order — newest / most relevant
    * first — so trimming takes a prefix, exactly like the prompt
    * shape's entry caps. */
  case Entries(header: String, lines: List[String], footer: String = "")

  /** The section's rendered text. */
  def rendered: String = this match {
    case Blob(t)          => t
    case Entries(h, l, f) => h + l.mkString + f
  }

  /** This body trimmed to fit `budget` tokens, or `None` when nothing
    * survives (a header with no entries is noise, not context).
    *
    * The largest prefix of `lines` whose FULL rendering fits wins —
    * counting the assembled text rather than summing per-line counts,
    * since the estimator is not additive across fragments. Blobs are
    * returned untouched. */
  def within(budget: Int, tokenizer: Tokenizer = HeuristicTokenizer): Option[SectionBody] = this match {
    case b: Blob => Some(b)
    case Entries(header, lines, footer) =>
      val kept = (lines.size to 0 by -1)
        .find(k => tokenizer.count(header + lines.take(k).mkString + footer) <= budget)
        .getOrElse(0)
      if (kept == 0) None
      else if (kept == lines.size) Some(this)
      else Some(Entries(header, lines.take(kept), footer))
  }
}
