package sigil.provider

import scala.util.matching.Regex

/**
 * Recognises refusal language in an agent's `respond.content`. Used
 * by the orchestrator (sigil bug #126) to enforce that a refusal is
 * only valid AFTER the agent has consulted `find_capability` — the
 * system prompt instructs this, but smaller / less-instruction-
 * following models drift to confident refusals without checking the
 * catalog. When the detector fires AND no `find_capability` was
 * called since the last user message, the orchestrator suppresses
 * the respond emission and substitutes a Tool-role `Failure`
 * diagnostic the agent reads on its next iteration.
 *
 * Apps wire a custom detector via [[sigil.Sigil.refusalDetector]] —
 * e.g. a moderation app where refusals are legitimate provides
 * `RefusalDetector.Never` so the framework never challenges; an app
 * with a broader phrasing vocabulary plugs in a richer regex set or
 * a classifier-backed implementation.
 */
trait RefusalDetector {

  /**
   * Inspect the respond's rendered text and return `true` when it
   * reads as a refusal. Conservative — false negatives are
   * preferable to false positives (a false positive challenges a
   * non-refusal and forces an unnecessary iteration; a false
   * negative misses a refusal but leaves the user's experience
   * unchanged).
   */
  def isRefusal(content: String): Boolean
}

object RefusalDetector {

  /**
   * Default pattern-matcher tuned against the bug #126 wire-log
   * scenario (Qwen3.6-35B refusing "switch to gpt-5.5" with
   * `I can't switch to GPT-5.5…`). Conservative enough that
   * shipped apps haven't observed false positives on productive
   * respond emissions in the bug's reference corpus.
   */
  case object Default extends RefusalDetector {
    private val patterns: List[Regex] = List(
      raw"^\s*(?i)i\s+(can'?t|cannot|am\s+(not\s+able|unable))\b".r,
      raw"^\s*(?i)i'?m\s+(not\s+able|unable)\b".r,
      raw"^\s*(?i)(unfortunately|sorry,?\s+but)\b".r,
      raw"\b(?i)i\s+don'?t\s+have\s+(access|the\s+ability)\b".r,
      raw"\b(?i)that'?s\s+(not\s+something|beyond\s+(my|what))\b".r
    )

    override def isRefusal(content: String): Boolean = {
      val text = content.trim
      if (text.isEmpty) false
      else patterns.exists(_.findFirstIn(text).isDefined)
    }
  }

  /**
   * Pass-through detector — never reports a refusal. Apps where
   * the agent is allowed (or expected) to refuse without first
   * consulting `find_capability` wire this to bypass the
   * challenge mechanism.
   */
  case object Never extends RefusalDetector {
    override def isRefusal(content: String): Boolean = false
  }
}
