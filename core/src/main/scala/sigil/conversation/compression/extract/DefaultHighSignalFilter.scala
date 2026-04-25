package sigil.conversation.compression.extract

/**
 * Default heuristic high-signal filter. Accepts messages that mention
 * personal identifiers, lifecycle events, preferences, concrete
 * numbers or dollar amounts. Rejects anything under 50 characters
 * outright (usually too short to carry a durable fact).
 *
 * Apps that want different heuristics (domain-specific vocabulary,
 * multilingual support) subclass and override `isHighSignal`.
 */
object DefaultHighSignalFilter extends HighSignalFilter {
  private val patterns: List[scala.util.matching.Regex] = List(
    raw"\bi\b.*\b(bought|purchased|paid|spent|earned|received|got)\b".r,
    raw"\bmy\b.*\b(name|wife|husband|dog|cat|car|house|apartment|salary|job|boss|favorite)\b".r,
    raw"\bi\b.*\b(live|work|moved|graduated|started|married|divorced|retired)\b".r,
    raw"\bi\b.*\b(prefer|like|love|hate|enjoy|dislike|allergic)\b".r,
    raw"\b(born|birthday|anniversary|promotion|pregnant|baby|engaged)\b".r,
    """\$\d+""".r,
    raw"\b\d{3,}\b".r
  )

  override def isHighSignal(userMessage: String): Boolean = {
    if (userMessage == null) false
    else if (userMessage.length < 50) false
    else {
      val lower = userMessage.toLowerCase
      patterns.exists(_.findFirstIn(lower).isDefined)
    }
  }
}
