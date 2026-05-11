package sigil.conversation.compression

import fabric.{Arr, Json, Obj, Null, Str}
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
 * Two complementary heuristics:
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
 * Returns the first signal that fires (identical-streak is
 * narrower; empty-streak is broader). Pure function — no IO, no
 * state — so it tests in isolation from the agent loop.
 */
object StallDetector {

  /** Detection thresholds. Tuned empirically against the live
    * wire-log scenarios that motivated bug #124. Apps with
    * different agent loop shapes override via the
    * `Sigil.stallDetector*` knobs. */
  val DefaultIdenticalThreshold: Int = 3
  val DefaultEmptyThreshold: Int     = 4

  /** Result of evaluating the tail. `reason` is non-empty when
    * `detected = true`; the reflector folds it into the
    * checkpoint's `stuckOn` field. */
  final case class Signal(detected: Boolean, reason: Option[String])

  object Signal {
    val Empty: Signal = Signal(detected = false, reason = None)
  }

  /** One unit of agent-side activity since the prior checkpoint.
    * The framework collects these from the event log in
    * chronological order; the detector walks them tail-first.
    *
    *   - `invoke` — the ToolInvoke event (carries name + input).
    *   - `result` — the paired ToolResults (`None` when the call
    *     orphan-settled or its result hasn't been recorded yet).
    *   - `resultMessage` — when the tool emitted free-form Tool-
    *     role Messages instead of ToolResults; surfaces empty-
    *     payload detection against rendered text. */
  final case class CallRecord(invoke: ToolInvoke,
                              result: Option[ToolResults],
                              resultMessage: Option[Message])

  /** Evaluate the tail of recent calls. Returns a positive signal
    * as soon as either heuristic fires; otherwise `Signal.Empty`. */
  def evaluate(tail: List[CallRecord],
               identicalThreshold: Int = DefaultIdenticalThreshold,
               emptyThreshold: Int = DefaultEmptyThreshold): Signal = {
    if (tail.isEmpty) Signal.Empty
    else {
      val identical = identicalStreak(tail, identicalThreshold)
      if (identical.detected) identical else emptyStreak(tail, emptyThreshold)
    }
  }

  private def identicalStreak(tail: List[CallRecord], threshold: Int): Signal = {
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
          reason   = Some(
            s"You've called `$name` $run times with the same arguments, each returning the same result. " +
              "Change your approach — try a different tool, ask the user for clarification via `respond`, or `cancel` the task."
          )
        )
      } else Signal.Empty
    }
  }

  private def emptyStreak(tail: List[CallRecord], threshold: Int): Signal = {
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
  }

  private def emitEmptySignal(count: Int, distinctNames: List[String]): Signal = {
    val toolList = distinctNames.take(5).map(n => s"`$n`").mkString(", ") +
      (if (distinctNames.size > 5) s" + ${distinctNames.size - 5} more" else "")
    Signal(
      detected = true,
      reason   = Some(
        s"Your last $count tool calls ($toolList) all returned empty or null results — no new information is flowing back. " +
          "Change your approach — try a different shape of tool (read a file, grep, ask the user), or `cancel` the task."
      )
    )
  }

  /** Stable JSON fingerprint of the invoke's input. Two invokes
    * with the same toolName + same input shape produce equal
    * fingerprints. */
  def inputFingerprint(invoke: ToolInvoke): String =
    invoke.input.map(_.json).map(canonicalize).map(_.toString).getOrElse("<no-input>")

  /** Output fingerprint — used by the identical-call detector to
    * tell apart "same call, same answer" (real stall) from "same
    * call, different answer" (legit re-query). For ToolResults
    * with a typed payload, fingerprint the canonical JSON; for
    * Tool-role Messages, fingerprint the text content; for
    * pending (no result), fingerprint `"<pending>"`. */
  def outputFingerprint(r: CallRecord): String = (r.result, r.resultMessage) match {
    case (Some(tr), _) if tr.typed.isDefined =>
      canonicalize(tr.typed.get).toString
    case (Some(tr), _) =>
      tr.summary.getOrElse("<no-output>")
    case (None, Some(m)) =>
      m.content.collect {
        case ResponseContent.Text(t)        => t
        case ResponseContent.Markdown(t)    => t
        case ResponseContent.Failure(t, _, _) => "FAIL:" + t
      }.mkString("\n").trim
    case _ => "<pending>"
  }

  /** True when the call's output carries no information.
    * Recognises empty arrays / objects / `null`, the empty-string
    * sentinel, and Tool-role Messages whose rendered content
    * collapses to whitespace. */
  def isEmpty(r: CallRecord): Boolean = (r.result, r.resultMessage) match {
    case (Some(tr), _) if tr.typed.isDefined => isEmptyJson(tr.typed.get)
    case (Some(tr), _) => tr.summary.forall(_.trim.isEmpty)
    case (None, Some(m)) =>
      m.content.collect {
        case ResponseContent.Text(t)     => t
        case ResponseContent.Markdown(t) => t
      }.mkString("").trim.isEmpty
    case _ => false  // pending / unknown — don't count as empty
  }

  private def isEmptyJson(j: Json): Boolean = j match {
    case Null      => true
    case s: Str    => s.value.trim.isEmpty
    case a: Arr    => a.value.isEmpty
    case o: Obj    =>
      // Treat objects with only conventionally-meaningless fields as empty.
      val meaningful = o.value.filterNot { case (k, _) => k.startsWith("_") }
      meaningful.isEmpty || meaningful.values.forall(isEmptyJson)
    case _         => false
  }

  /** Best-effort canonical JSON for fingerprinting — sorts object
    * keys so the fingerprint is order-invariant. */
  private def canonicalize(j: Json): Json = j match {
    case o: Obj =>
      val sorted = o.value.toSeq.sortBy(_._1).map { case (k, v) => k -> canonicalize(v) }
      Obj(scala.collection.immutable.VectorMap.from(sorted))
    case a: Arr => Arr(a.value.map(canonicalize))
    case other  => other
  }
}
