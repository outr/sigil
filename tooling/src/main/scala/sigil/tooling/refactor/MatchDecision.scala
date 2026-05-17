package sigil.tooling.refactor

import fabric.rw.*

/**
 * Per-match decision returned by a refactor worker for a single
 * grep hit. The worker reads the file + the instruction, then for
 * each matched line emits one of three outcomes:
 *
 *   - `Edited` — the worker proposes a text replacement. `newText`
 *     is the replacement; `range` (0-based, [start, end)) identifies
 *     the byte / character span to overwrite.
 *   - `Skipped` — the worker judged that this particular match
 *     shouldn't be edited (e.g. it falls inside a code block the
 *     instruction excludes, or the change is already applied).
 *   - `Failed` — the worker couldn't make a clean decision
 *     (ambiguous instruction, malformed match, etc.). `reason`
 *     explains.
 *
 * Aggregated across files into a [[FileRefactorReport]] that the
 * main `RefactorWithInstructionTool` returns to the agent.
 */
case class MatchDecision(matchedLine: Int,
                         action: MatchAction,
                         reason: String,
                         oldText: String,
                         newText: Option[String] = None,
                         /** 0-based character index of the start of
                           * the edit span on `matchedLine`. None
                           * when `action != Edited`. */
                         startChar: Option[Int] = None,
                         /** 0-based character index of the end of
                           * the edit span on `matchedLine`. None
                           * when `action != Edited`. */
                         endChar: Option[Int] = None) derives RW

enum MatchAction derives RW {
  case Edited, Skipped, Failed
}
