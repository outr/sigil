package sigil.conversation.compression

import fabric.{Arr, Bool, Json, NumDec, NumInt, Obj, Null, Str}
import fabric.rw.*
import sigil.event.{Message, ToolInvoke, ToolResults}
import sigil.tool.model.ResponseContent

/**
 * Objective stall-detection over the agent's recent tool-call tail.
 * Pairs with the progress-checkpoint reflector's self-assessment:
 * `effectiveProgress = agentClaim && !stall.detected`. The
 * reflector trusting the agent alone produces false positives —
 * agents cheerfully self-report "yes I made progress" while
 * looping identical tool calls that return identical empty
 * payloads (sigil bug #124's wire-log scenario).
 *
 * Three complementary heuristics, fired in narrowest-first order:
 *
 *   1. **Identical-call streak** — three or more consecutive
 *      tool calls with the same `(toolName, inputJson)` pair AND
 *      same output shape (`typed` JSON value or rendered text).
 *      The agent is asking the same question repeatedly and
 *      getting the same answer.
 *
 *   2. **Empty-payload streak** — four or more consecutive
 *      tool calls whose `ToolResults.typed` is structurally empty
 *      (empty array, empty object, `null`, or a Tool-role Message
 *      whose content collapses to whitespace). The calls may be
 *      distinct, but no information is flowing back.
 *
 *   3. **Low-information streak** — three or more consecutive
 *      non-empty tool outputs whose atomized payloads have high
 *      pairwise Jaccard overlap (≥ `lowInfoJaccard`, default
 *      0.7). Inputs may differ, tool names may differ, payloads
 *      are individually non-empty — but the agent is producing
 *      mostly the same set of facts over and over. Catches the
 *      "confirmation grep" failure mode: the agent has enough
 *      to answer, keeps re-querying for facts already in hand.
 *
 * Returns the first signal that fires (identical → empty →
 * low-info, ordered by specificity). Pure function — no IO, no
 * state — so it tests in isolation from the agent loop.
 */
object StallDetector {

  /**
   * Detection thresholds. Tuned empirically against the live
   * wire-log scenarios that motivated bug #124. Apps with
   * different agent loop shapes override via the
   * `Sigil.stallDetector*` knobs.
   */
  val DefaultIdenticalThreshold: Int = 3
  val DefaultEmptyThreshold: Int = 4
  val DefaultLowInfoThreshold: Int = 3

  /**
   * Minimum pairwise Jaccard between consecutive non-empty
   * outputs for the low-information heuristic to count them as
   * overlapping. 0.7 = at least 70 % of the atomized payload
   * tokens shared.
   */
  val DefaultLowInfoJaccard: Double = 0.7

  /**
   * Minimum atoms per output before the low-information
   * heuristic considers a pair. Below this, a single shared
   * token trips false positives — tiny outputs (`{"ok":true}`
   * style success markers) overlap trivially.
   */
  val DefaultLowInfoMinAtoms: Int = 5

  /**
   * Result of evaluating the tail. `reason` is non-empty when
   * `detected = true`; the reflector folds it into the
   * checkpoint's `stuckOn` field.
   */
  final case class Signal(detected: Boolean, reason: Option[String])

  object Signal {
    val Empty: Signal = Signal(detected = false, reason = None)
  }

  /**
   * One unit of agent-side activity since the prior checkpoint.
   * The framework collects these from the event log in
   * chronological order; the detector walks them tail-first.
   *
   *   - `invoke` — the ToolInvoke event (carries name + input).
   *   - `result` — the paired ToolResults (`None` when the call
   *     orphan-settled or its result hasn't been recorded yet).
   *   - `resultMessage` — when the tool emitted free-form Tool-
   *     role Messages instead of ToolResults; surfaces empty-
   *     payload detection against rendered text.
   */
  final case class CallRecord(invoke: ToolInvoke,
                              result: Option[ToolResults],
                              resultMessage: Option[Message])

  /**
   * Evaluate the tail of recent calls. Returns a positive signal
   * as soon as any heuristic fires (narrowest first); otherwise
   * `Signal.Empty`.
   */
  def evaluate(tail: List[CallRecord],
               identicalThreshold: Int = DefaultIdenticalThreshold,
               emptyThreshold: Int = DefaultEmptyThreshold,
               lowInfoThreshold: Int = DefaultLowInfoThreshold,
               lowInfoJaccard: Double = DefaultLowInfoJaccard,
               lowInfoMinAtoms: Int = DefaultLowInfoMinAtoms): Signal =
    if (tail.isEmpty) Signal.Empty
    else {
      val identical = identicalStreak(tail, identicalThreshold)
      if (identical.detected) identical
      else {
        val empty = emptyStreak(tail, emptyThreshold)
        if (empty.detected) empty
        else lowInformationStreak(tail, lowInfoThreshold, lowInfoJaccard, lowInfoMinAtoms)
      }
    }

  private def identicalStreak(tail: List[CallRecord], threshold: Int): Signal =
    if (tail.size < threshold) Signal.Empty
    else {
      // Compute fingerprint = (toolName, inputJson, outputFingerprint).
      val fingerprints = tail.reverseIterator.map { r =>
        (r.invoke.toolName.value, inputFingerprint(r.invoke), outputFingerprint(r))
      }.toList
      var run = 1
      var head = fingerprints.head
      val it = fingerprints.iterator
      it.next()
      while (it.hasNext && run < threshold) {
        val next = it.next()
        if (next == head) run += 1
        else { head = next; run = 1 }
      }
      if (run >= threshold) {
        val (name, _, _) = head
        Signal(
          detected = true,
          reason = Some(
            s"You've called `$name` $run times with the same arguments, each returning the same result. " +
              "Change your approach — try a different tool, ask the user for clarification via `respond`, or `cancel` the task."
          )
        )
      } else Signal.Empty
    }

  private def emptyStreak(tail: List[CallRecord], threshold: Int): Signal =
    if (tail.size < threshold) Signal.Empty
    else {
      var run = 0
      val it = tail.reverseIterator
      var lastName: Option[String] = None
      val distinctNames = scala.collection.mutable.LinkedHashSet.empty[String]
      while (it.hasNext && run < threshold) {
        val r = it.next()
        if (isEmpty(r)) {
          run += 1
          distinctNames += r.invoke.toolName.value
          lastName = Some(r.invoke.toolName.value)
        } else {
          // Reset — only count strictly trailing empties.
          return if (run >= threshold) emitEmptySignal(run, distinctNames.toList) else Signal.Empty
        }
      }
      if (run >= threshold) emitEmptySignal(run, distinctNames.toList) else Signal.Empty
    }

  /**
   * Heuristic 3 — the agent's last N non-empty outputs share
   * most of their atomized payload across consecutive pairs.
   * Catches "you have enough to answer, you're now just
   * confirming" patterns that #124's identical / empty
   * heuristics miss when each call's input genuinely differs
   * (slight keyword variations on grep, follow-up reads on the
   * same file, etc.).
   */
  def lowInformationStreak(tail: List[CallRecord],
                           threshold: Int,
                           jaccardThreshold: Double,
                           minAtoms: Int): Signal =
    if (tail.size < threshold) Signal.Empty
    else {
      // Walk tail-first, pick the most-recent N non-empty calls
      // each with at least `minAtoms` worth of payload. Below
      // `minAtoms` we don't have enough signal to compare —
      // tiny success-flag outputs (`{"ok":true}`) trivially
      // overlap and produce false positives.
      val recent = tail.reverseIterator
        .filterNot(isEmpty)
        .map(r => r -> outputAtoms(r))
        .filter { case (_, atoms) => atoms.size >= minAtoms }
        .take(threshold)
        .toList
      if (recent.size < threshold) Signal.Empty
      else {
        // Every consecutive pair must have Jaccard ≥ threshold.
        // Pairs(0,1) + Pairs(1,2) for threshold=3.
        val pairs = recent.sliding(2).toList.collect {
          case (_, a) :: (_, b) :: Nil => jaccard(a, b)
        }
        if (pairs.nonEmpty && pairs.forall(_ >= jaccardThreshold)) {
          val names = recent.map(_._1.invoke.toolName.value).distinct
          val toolList = names.take(5).map(n => s"`$n`").mkString(", ") +
            (if (names.size > 5) s" + ${names.size - 5} more" else "")
          val avgOverlap = math.round(pairs.sum / pairs.size * 100).toInt
          Signal(
            detected = true,
            reason = Some(
              s"Your last ${recent.size} non-empty tool calls ($toolList) returned ~$avgOverlap% overlapping data — " +
                "you're confirming facts already in hand rather than learning anything new. Stop gathering and call " +
                "`respond` with what you have, or shift to a different shape of action (edit/save/send) if the user " +
                "asked you to DO something."
            )
          )
        } else Signal.Empty
      }
    }

  /**
   * Jaccard similarity of two atom sets. Defined as
   * `|a ∩ b| / |a ∪ b|`. Empty + empty → 1.0 (degenerate); empty
   * + non-empty → 0.0.
   */
  def jaccard(a: Set[String], b: Set[String]): Double =
    if (a.isEmpty && b.isEmpty) 1.0
    else if (a.isEmpty || b.isEmpty) 0.0
    else {
      val inter = a.intersect(b).size.toDouble
      val union = a.union(b).size.toDouble
      inter / union
    }

  /**
   * Atomize a call's output — typed JSON payload preferred, then
   * `ToolResults.summary`, then Tool-role Message content. Used
   * by [[lowInformationStreak]] to compare payloads across
   * heterogenous tool shapes.
   */
  def outputAtoms(r: CallRecord): Set[String] = (r.result, r.resultMessage) match {
    case (Some(tr), _) if tr.typed.isDefined => jsonAtoms(tr.typed.get)
    case (Some(tr), _) => tr.summary.map(textAtoms).getOrElse(Set.empty)
    case (None, Some(m)) => m.content.flatMap {
        case ResponseContent.Text(t) => textAtoms(t)
        case ResponseContent.Markdown(t) => textAtoms(t)
        case ResponseContent.Code(code, _) => textAtoms(code)
        case _ => Set.empty[String]
      }.toSet
    case _ => Set.empty
  }

  /**
   * Recursively atomize a JSON payload. Strings tokenize via
   * [[textAtoms]]; numbers / bools / null become their string
   * form; arrays and objects flatten their atoms into the
   * combined set. Object keys are NOT atomized — they're
   * structural metadata, not content.
   */
  def jsonAtoms(j: Json): Set[String] = j match {
    case Null => Set("null")
    case b: Bool => Set(b.value.toString)
    case n: NumInt => Set(n.value.toString)
    case n: NumDec => Set(n.value.toString)
    case s: Str => textAtoms(s.value)
    case a: Arr => a.value.iterator.flatMap(jsonAtoms).toSet
    case o: Obj => o.value.values.iterator.flatMap(jsonAtoms).toSet
  }

  /**
   * Tokenize a text fragment into a set of normalized atoms.
   * Splits on non-alphanumeric runs, lowercases, drops tokens
   * shorter than 3 chars (noise; doesn't carry meaning).
   */
  def textAtoms(text: String): Set[String] =
    text.split("[^A-Za-z0-9_]+").iterator
      .filter(_.length >= 3)
      .map(_.toLowerCase)
      .toSet

  private def emitEmptySignal(count: Int, distinctNames: List[String]): Signal = {
    val toolList = distinctNames.take(5).map(n => s"`$n`").mkString(", ") +
      (if (distinctNames.size > 5) s" + ${distinctNames.size - 5} more" else "")
    Signal(
      detected = true,
      reason = Some(
        s"Your last $count tool calls ($toolList) all returned empty or null results — no new information is flowing back. " +
          "Change your approach — try a different shape of tool (read a file, grep, ask the user), or `cancel` the task."
      )
    )
  }

  /**
   * Stable JSON fingerprint of the invoke's input. Two invokes
   * with the same toolName + same input shape produce equal
   * fingerprints.
   */
  def inputFingerprint(invoke: ToolInvoke): String =
    invoke.input.map(_.json).map(canonicalize).map(_.toString).getOrElse("<no-input>")

  /**
   * Output fingerprint — used by the identical-call detector to
   * tell apart "same call, same answer" (real stall) from "same
   * call, different answer" (legit re-query). For ToolResults
   * with a typed payload, fingerprint the canonical JSON; for
   * Tool-role Messages, fingerprint the text content; for
   * pending (no result), fingerprint `"<pending>"`.
   */
  def outputFingerprint(r: CallRecord): String = (r.result, r.resultMessage) match {
    case (Some(tr), _) if tr.typed.isDefined =>
      canonicalize(tr.typed.get).toString
    case (Some(tr), _) =>
      tr.summary.getOrElse("<no-output>")
    case (None, Some(m)) =>
      val text = m.content.collect {
        case ResponseContent.Text(t) => t
        case ResponseContent.Markdown(t) => t
      }.mkString("\n").trim
      m.disposition match {
        case _: sigil.event.MessageDisposition.Failure => "FAIL:" + text
        case sigil.event.MessageDisposition.Success => text
      }
    case _ => "<pending>"
  }

  /**
   * True when the call's output carries no information.
   * Recognises empty arrays / objects / `null`, the empty-string
   * sentinel, and Tool-role Messages whose rendered content
   * collapses to whitespace.
   */
  def isEmpty(r: CallRecord): Boolean = (r.result, r.resultMessage) match {
    case (Some(tr), _) if tr.typed.isDefined => isEmptyJson(tr.typed.get)
    case (Some(tr), _) => tr.summary.forall(_.trim.isEmpty)
    case (None, Some(m)) =>
      m.content.collect {
        case ResponseContent.Text(t) => t
        case ResponseContent.Markdown(t) => t
      }.mkString("").trim.isEmpty
    case _ => false // pending / unknown — don't count as empty
  }

  private def isEmptyJson(j: Json): Boolean = j match {
    case Null => true
    case s: Str => s.value.trim.isEmpty
    case a: Arr => a.value.isEmpty
    case o: Obj =>
      // Treat objects with only conventionally-meaningless fields as empty.
      val meaningful = o.value.filterNot { case (k, _) => k.startsWith("_") }
      meaningful.isEmpty || meaningful.values.forall(isEmptyJson)
    case _ => false
  }

  /**
   * Best-effort canonical JSON for fingerprinting — sorts object
   * keys so the fingerprint is order-invariant.
   */
  private def canonicalize(j: Json): Json = j match {
    case o: Obj =>
      val sorted = o.value.toSeq.sortBy(_._1).map { case (k, v) => k -> canonicalize(v) }
      Obj(scala.collection.immutable.VectorMap.from(sorted))
    case a: Arr => Arr(a.value.map(canonicalize))
    case other => other
  }
}
