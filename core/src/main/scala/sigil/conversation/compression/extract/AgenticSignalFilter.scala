package sigil.conversation.compression.extract

import scala.util.matching.Regex

/**
 * High-signal filter tuned for coding / agentic corpora, where
 * [[DefaultHighSignalFilter]]'s personal-assistant idioms ("I bought",
 * "my wife", dollar amounts) reject essentially every turn. Passes:
 *
 *   - turns that settled tool mutations
 *     ([[ExtractionTurn.settledMutations]] non-empty) — a turn that
 *     changed external state is worth mining whatever its text said;
 *   - decision / constraint language in either half of the turn
 *     ("decided", "chose", "instead of", "must not", "convention",
 *     "always / never use"), error-name mentions
 *     (`FooException` / `BarError`), and version pins;
 *   - explicit user corrections shortly after an agent action
 *     (negation / correction markers in the user message when the
 *     turn has an agent response).
 *
 * NOT the framework default — [[DefaultHighSignalFilter]] stays the
 * default so existing consumers' extraction volume is unchanged. Apps
 * with agentic conversations wire this (or
 * `HighSignalFilter.any(DefaultHighSignalFilter, AgenticSignalFilter)`)
 * onto their [[StandardMemoryExtractor]].
 */
object AgenticSignalFilter extends HighSignalFilter {

  private val decisionPatterns: List[Regex] = List(
    raw"\b(decided|decide|chose|chosen|choosing|picked|opted|settled on|going with|went with|let's use|we'll use)\b".r,
    raw"\b(instead of|rather than|switch(ed|ing)? to|migrat(e|ed|ing) to)\b".r,
    raw"\b(must not|must never|must always|never use|always use|do not use|don't use|should always|should never|from now on)\b".r,
    raw"\b(convention|invariant|constraint|requirement|prerequisite|policy)\b".r,
    raw"\b(rejects?|requires?|expects?|only supports?|not supported|deprecated|incompatible)\b".r
  )

  /** Error / exception class names — `NullPointerException`,
    * `RequestOverBudgetException`, `TypeError`. Case-sensitive on the
    * original text: the CamelCase shape is the signal. */
  private val errorNamePattern: Regex = raw"\b[A-Z][A-Za-z0-9]*(Exception|Error)\b".r

  /** Version pins — three-component versions (`4.31.1`,
    * `1.12.10-RC2`) or `v`-prefixed tags (`v2`, `v3.8`). Deliberately
    * NOT bare `N.N` — too many casual number collisions. */
  private val versionPinPattern: Regex = raw"(\b\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?\b|\bv\d+(\.\d+)*\b)".r

  private val correctionPatterns: List[Regex] = List(
    raw"^\s*(no|nope|wrong|wait|actually|stop)\b".r,
    raw"\b(that's (wrong|incorrect|not right|not what)|that is (wrong|incorrect|not right|not what))\b".r,
    raw"\b(not what i (asked|meant|wanted|said)|i meant|i didn't (ask|mean|want|say))\b".r,
    raw"\b(undo that|revert that|don't do that|shouldn't have|should not have)\b".r
  )

  private def matchesAny(patterns: List[Regex], lower: String): Boolean =
    patterns.exists(_.findFirstIn(lower).isDefined)

  private def hasDecisionSignal(text: String): Boolean = {
    if (text == null || text.isEmpty) false
    else matchesAny(decisionPatterns, text.toLowerCase) ||
      errorNamePattern.findFirstIn(text).isDefined ||
      versionPinPattern.findFirstIn(text).isDefined
  }

  private def hasCorrectionSignal(userMessage: String): Boolean =
    userMessage != null && userMessage.nonEmpty && matchesAny(correctionPatterns, userMessage.toLowerCase)

  override def isHighSignal(userMessage: String): Boolean =
    hasDecisionSignal(userMessage) || hasCorrectionSignal(userMessage)

  override def isHighSignal(turn: ExtractionTurn): Boolean =
    turn.settledMutations.nonEmpty ||
      hasDecisionSignal(turn.userMessage) ||
      hasDecisionSignal(turn.agentResponse) ||
      (turn.agentResponse.nonEmpty && hasCorrectionSignal(turn.userMessage))
}
