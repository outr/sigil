package sigil.tooling.dispatch

import fabric.Json
import fabric.rw.*

/**
 * Per-item outcome of one [[DispatchWorkersTool]] worker invocation.
 * Sealed trait union so the agent's next iteration can pattern-match
 * on the specific failure mode rather than parsing a string.
 *
 *   - [[Success]]          — both LLM and (if present) script step
 *     ran. `output` carries the script's return value (or the LLM's
 *     parsed output when the pipeline had no script step).
 *   - [[Stale]]             — the script step returned a `Stale`
 *     marker (typically from `edit_file`'s safe-edit hash mismatch).
 *     The item didn't apply; the worker isn't broken — the world
 *     changed since the LLM proposed the edit. Carries the most
 *     recent observed hash so the caller can decide whether to
 *     retry.
 *   - [[ValidationFailed]] — the LLM's response didn't match
 *     `outputSchema` (or the response was empty / malformed).
 *   - [[ScriptError]]      — the script step raised an exception.
 *     Carries the failure message.
 *   - [[Skipped]]          — the framework refused to run this item
 *     (e.g. ScriptStep was requested but the host doesn't mix in
 *     ScriptSigil). Carries a reason.
 */
sealed trait WorkerResult derives RW {
  def item: Json
}

object WorkerResult {

  case class Success(item: Json, output: Json) extends WorkerResult derives RW

  case class Stale(item: Json, currentHash: String) extends WorkerResult derives RW

  case class ValidationFailed(item: Json, reason: String) extends WorkerResult derives RW

  case class ScriptError(item: Json, message: String) extends WorkerResult derives RW

  case class Skipped(item: Json, reason: String) extends WorkerResult derives RW
}
