package sigil.tool

/**
 * Bug #131 — annotation mix-ins by tool family. Centralises the
 * (readOnly, destructive, idempotent, openWorld) tuple per
 * category so adding a new tool of a given shape doesn't require
 * remembering four overrides.
 */

/** Read-only filesystem / repository inspection tools — `grep`,
  * `glob`, `read_file`, `git_status`, `git_log`, etc. The call has
  * no side effect; the underlying file contents CAN change between
  * calls, so the read is open-world but not idempotent. */
trait ReadOnlyExternalTool extends Tool {
  override def readOnly: Boolean    = true
  override def destructive: Boolean = false
  override def openWorld: Boolean   = true
  override def idempotent: Boolean  = false
}

/** Read-only intra-Sigil tools that touch only Sigil-managed state
  * (memory store, conversation events, lightdb collections). Same
  * shape as [[ReadOnlyExternalTool]] but `openWorld = false` —
  * nothing outside Sigil's process can mutate the data. */
trait ReadOnlyInternalTool extends Tool {
  override def readOnly: Boolean    = true
  override def destructive: Boolean = false
  override def openWorld: Boolean   = false
  override def idempotent: Boolean  = true
}

/** Destructive filesystem / shell / repository write tools —
  * `bash`, `edit_file`, `write_file`, `delete_file`, `git_commit`.
  * Mutates external state, calling twice doesn't produce the same
  * result (the mutation already happened). */
trait DestructiveExternalTool extends Tool {
  override def destructive: Boolean = true
  override def openWorld: Boolean   = true
  override def idempotent: Boolean  = false
  override def readOnly: Boolean    = false
}

/** Destructive intra-Sigil tools that mutate Sigil's own storage
  * (memory upsert, conversation deletion). */
trait DestructiveInternalTool extends Tool {
  override def destructive: Boolean = true
  override def openWorld: Boolean   = false
  override def idempotent: Boolean  = false
  override def readOnly: Boolean    = false
}

/** Open-world tools that touch external state without mutating it —
  * `web_fetch`, `web_search`, `consult`. Network round-trip, but
  * no side effect on the remote system from Sigil's perspective. */
trait NetworkReadOnlyTool extends Tool {
  override def readOnly: Boolean    = true
  override def destructive: Boolean = false
  override def openWorld: Boolean   = true
  override def idempotent: Boolean  = false
}
