package sigil.conversation.compression

import sigil.conversation.ContextFrame
import sigil.participant.ParticipantId

/**
 * Detects the "paraphrase without action" failure mode: an agent
 * emitting several consecutive `respond` drafts whose normalized
 * content is near-identical, with no non-respond tool execution
 * between them. The pattern is the precursor to a token-level
 * repetition loop in weaker models (qwen-9b-class) — the prior
 * paraphrases prime the next iteration to generate yet another
 * paraphrase.
 *
 * The detector runs at curation time over the turn's frame
 * history. On a hit, the curator injects a system-level
 * observation into the next prompt naming the pattern so the
 * model can see (and respond to) the loop instead of being
 * silently cleaned up.
 *
 * Tuning knobs:
 *
 *   - `windowSize` — minimum trailing agent Text frames required
 *     before considering the loop active. Default 4.
 *   - `similarityThreshold` — pairwise Jaccard score (normalized
 *     token sets) above which two drafts count as near-duplicates.
 *     Default 0.7 — high enough that genuinely different
 *     paragraphs don't trip, low enough that the live "Let me
 *     pull up the full list..." pattern fires.
 *   - `escalationThreshold` — at this trailing-draft count, the
 *     observation's tone shifts from "you appear stuck" to
 *     "second occurrence — the framework will escalate".
 *     Default 5 (one beyond `windowSize`).
 */
final case class ParaphraseLoopDetector(windowSize: Int = 4,
                                        similarityThreshold: Double = 0.7,
                                        escalationThreshold: Int = 5) {

  /**
   * Returns the paraphrase pattern when the agent's most recent
   * frames form a same-shape draft sequence with no real tool
   * execution between them. `None` when the trailing slice doesn't
   * meet the threshold.
   */
  def detect(frames: Vector[ContextFrame], agentId: ParticipantId): Option[ParaphraseLoopDetector.Pattern] = {
    val trailing = collectTrailingResponds(frames, agentId)
    if (trailing.size < windowSize) None
    else {
      val sample = trailing.take(windowSize)
      if (sample.size < 2) None
      else if (pairsAtOrAbove(sample, similarityThreshold)) Some(ParaphraseLoopDetector.Pattern(
        count = trailing.size,
        samples = trailing.take(3),
        escalated = trailing.size >= escalationThreshold
      ))
      else None
    }
  }

  /**
   * Walk newest→oldest. Collect agent's Text frames; stop when a
   * ToolCall, ToolResult, System, Reasoning frame, or a non-agent
   * Text frame appears. Returns newest-first.
   */
  private def collectTrailingResponds(frames: Vector[ContextFrame], agentId: ParticipantId): List[String] = {
    val out = scala.collection.mutable.ListBuffer.empty[String]
    val it = frames.reverseIterator
    var stopped = false
    while (it.hasNext && !stopped)
      it.next() match {
        case t: ContextFrame.Text if t.participantId == agentId => out += t.content
        case _: ContextFrame.Text => stopped = true // user turn — pattern boundary
        case _: ContextFrame.ToolCall => stopped = true // real action taken
        case _: ContextFrame.ToolResult => stopped = true // real action taken
        case _ => () // System / Reasoning — skip but keep scanning
      }
    out.toList
  }

  private def pairsAtOrAbove(texts: List[String], threshold: Double): Boolean = {
    val tokenized = texts.map(normalize)
    val pairs = for {
      i <- tokenized.indices
      j <- (i + 1) until tokenized.size
    } yield jaccard(tokenized(i), tokenized(j))
    pairs.exists(_ >= threshold)
  }

  private def normalize(text: String): Set[String] =
    text.toLowerCase
      .replaceAll("[^a-z0-9\\s]", " ")
      .split("\\s+")
      .filter(_.nonEmpty)
      .toSet

  private def jaccard(a: Set[String], b: Set[String]): Double = {
    val union = (a | b).size
    if (union == 0) 0.0 else (a & b).size.toDouble / union.toDouble
  }
}

object ParaphraseLoopDetector {

  /**
   * What the detector hands back when it fires. The curator's
   * renderer turns this into the system-prompt observation.
   *
   *   - `count` — total consecutive paraphrase drafts at the tail.
   *   - `samples` — first three draft texts (newest-first).
   *   - `escalated` — `true` once `count` reaches the escalation
   *     threshold; observation copy shifts to a stronger warning.
   */
  final case class Pattern(count: Int, samples: List[String], escalated: Boolean) {

    /**
     * Render the observation text the curator injects into
     * `TurnInput.extraContext`. Keep it self-contained so the
     * model reads it without needing extra prompt context.
     */
    def render(): String = {
      val header =
        if (escalated)
          s"[FRAMEWORK OBSERVATION — SECOND OCCURRENCE] You've now emitted $count consecutive paraphrase drafts without invoking a non-respond tool. The framework will halt this turn with a clarification ask to the user if this continues."
        else
          s"[FRAMEWORK OBSERVATION] Your last $count messages have all been near-duplicate `respond` drafts promising to take an action, but no execution tool was invoked between them."
      val sampleBlock = samples.zipWithIndex.map { case (s, i) =>
        val snippet = if (s.length <= 120) s else s.take(120) + "…"
        s"  ${i + 1}. \"$snippet\""
      }.mkString("\n")
      val guidance =
        "This pattern indicates you're stuck in a planning-without-acting loop. Your next tool call MUST be a non-respond tool that advances the task (e.g. `read_file`, `lsp_workspace_symbols`, `grep`, `bsp_compile`) — NOT another `respond` announcing what you'll do.\n" +
          "If you genuinely don't know what to do next, set `endsTurn = true` on your respond and ask the user a specific question. Don't paraphrase your prior plan."
      s"$header\n\nSample:\n$sampleBlock\n\n$guidance"
    }
  }

  /**
   * Stable [[sigil.conversation.ContextKey]] value the curator uses
   * when injecting the observation into `TurnInput.extraContext`.
   * Apps reading `extraContext` can filter on this key.
   */
  val ContextKeyValue: String = "_paraphraseObservation"
}
