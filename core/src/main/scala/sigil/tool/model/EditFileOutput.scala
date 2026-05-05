package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.fs.EditFileTool]].
 *
 * Five terminal states:
 *   - `Success(replacements, hash?)` — edit committed; `hash` is
 *     the post-edit SHA-256 (`Some` when safe-edit fed
 *     `expectedHash`, `None` for unconditional edits).
 *   - `NotFound` — `oldString` wasn't present in the file.
 *   - `NotUnique(occurrences)` — `oldString` matched multiple
 *     times and `replaceAll = false`; the agent can retry with
 *     `replaceAll = true` or a more specific anchor.
 *   - `Stale(currentHash, currentContent)` — safe-edit detected a
 *     newer version on disk; the agent should re-evaluate against
 *     `currentContent` and retry.
 *   - `FileNotFound` — safe-edit attempted on a file that no
 *     longer exists.
 */
enum EditFileOutput derives RW {
  case Success(replacements: Int, hash: Option[String])
  case NotFound
  case NotUnique(occurrences: Int)
  case Stale(currentHash: String, currentContent: String)
  case FileNotFound
}
