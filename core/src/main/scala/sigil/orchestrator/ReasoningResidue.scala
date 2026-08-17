package sigil.orchestrator

/**
 * Separates a model's spoken prose from the reasoning residue that
 * shares the same wire field.
 *
 * Reasoning-capable models served over chat completions split their
 * output into `reasoning_content` and `content`, and the boundary
 * between the two is a token the backend has to recognise. Backends
 * routinely mis-split it: the tail of the thinking block and its
 * closing tag arrive as ordinary assistant `content` (llama.cpp serving
 * Qwen emits `"4"`, `"\n"`, `"</think>"`, `"\n\n"` there before the
 * tool call it was reasoning toward). That text is internal monologue,
 * not something the model said to anyone, so it must never surface as a
 * user-visible Message or return as a frame the next iteration reads.
 *
 * A reasoning tag in the prose marks exactly where the monologue ends:
 * everything through a close tag belongs to the thinking block, and
 * everything from an unmatched open tag onward does too. What survives
 * is what the model actually spoke — usually nothing, sometimes a real
 * preamble that followed the boundary in the same emission window.
 */
object ReasoningResidue {

  /** Reasoning-block markers observed across served open-weight models.
    * Matched case-insensitively. */
  private val Tags: List[String] = List("think", "thinking", "reasoning", "thought")

  private val closePattern =
    Tags.map(t => s"</$t>").mkString("(?i)(?:", "|", ")").r
  private val openPattern =
    Tags.map(t => s"<$t>").mkString("(?i)(?:", "|", ")").r

  /**
   * Drop the reasoning residue from `text`, returning only the prose
   * the model spoke. Empty when the whole fragment was residue.
   */
  def strip(text: String): String =
    closePattern.findAllMatchIn(text).toList.lastOption match {
      case Some(m) => text.substring(m.end).stripLeading
      case None =>
        openPattern.findFirstMatchIn(text) match {
          case Some(m) => text.substring(0, m.start).stripTrailing
          case None    => text
        }
    }

  /** Whether `text` carries prose once its reasoning residue is gone. */
  def spoken(text: String): Boolean = strip(text).strip.nonEmpty
}
