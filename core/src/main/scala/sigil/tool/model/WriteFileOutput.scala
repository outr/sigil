package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.fs.WriteFileTool]].
 *
 * Three terminal states:
 *   - `Success(bytesWritten, hash?)` — write committed; `hash` is
 *     the safe-edit hash of the new contents (`Some` when the
 *     write went through `writeIfMatch`, `None` for unconditional
 *     writes).
 *   - `Stale(currentHash, currentContent)` — safe-edit detected a
 *     newer version on disk; the agent should re-evaluate against
 *     `currentContent` and retry.
 *   - `NotFound` — safe-edit attempted on a file that no longer
 *     exists.
 */
enum WriteFileOutput derives RW {
  case Success(bytesWritten: Long, hash: Option[String])
  case Stale(currentHash: String, currentContent: String)
  case NotFound
}
