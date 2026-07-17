package sigil.conversation

import sigil.event.Message

/**
 * Picks the objective a progress reflection should judge against (#320).
 *
 * The reflection must reflect on what the agent is *accomplishing*, not
 * on the latest thing the user typed. A short continuation —
 * `"proceed"`, `"yes"`, `"go on"` — advances the current objective; it
 * doesn't replace it. Treating it as "the request" drops the goal and
 * makes even a strong reflector report "vague instruction" and ask for
 * clarification, cancelling work that was actually on track.
 */
object ProgressTaskSelector {

  /**
   * Bare acknowledgements/continuations that carry no objective of
   * their own. Matched case-insensitively after stripping punctuation
   * and collapsing whitespace.
   */
  val Continuations: Set[String] = Set(
    "proceed",
    "continue",
    "go on",
    "go ahead",
    "keep going",
    "carry on",
    "yes",
    "y",
    "yep",
    "yeah",
    "ok",
    "okay",
    "k",
    "sure",
    "do it",
    "next",
    "go",
    "please continue",
    "please proceed"
  )

  def isContinuation(text: String): Boolean = {
    val norm = text.trim.toLowerCase.replaceAll("\\p{Punct}+", " ").replaceAll("\\s+", " ").trim
    norm.nonEmpty && Continuations.contains(norm)
  }

  /**
   * From chronological user messages, return the substantive task — the
   * most-recent non-continuation message, falling back to the latest if
   * every message is a continuation — and the latest directive when it
   * is a distinct continuation (so the prompt can render both).
   */
  def select(userMessages: List[Message], textOf: Message => String): (Option[Message], Option[Message]) = {
    val latest = userMessages.lastOption
    val substantive = userMessages.reverseIterator.find(m => !isContinuation(textOf(m))).orElse(latest)
    val directive = latest.filter(l => substantive.exists(_._id.value != l._id.value))
    (substantive, directive)
  }
}
