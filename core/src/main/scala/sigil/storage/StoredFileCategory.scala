package sigil.storage

import fabric.rw.*

/**
 * What kind of artifact a [[StoredFile]] holds. Used by the framework
 * for filtering at retrieval time and by maintenance tasks for
 * scoped sweeps.
 *
 * Apps surfacing a "files" UI typically default to listing only
 * `UserAttachment` entries — log-shaped tool output and externalized
 * message-content blocks shouldn't pollute the user's view of their
 * own uploads. The `Sigil.findStoredFiles` overload accepts an
 * optional category filter for that purpose.
 */
enum StoredFileCategory derives RW {
  /** Files the user uploaded — images, PDFs, attachments, anything
    * the user opted to persist. Default in "show me my files" UI
    * surfaces. Persistent (no `expiresAt` by default). */
  case UserAttachment

  /** Oversized message-content blocks that
    * `ContentExternalizationTransform` lifted out of the inline event
    * payload. Persistent (the originating Message references them
    * forever). */
  case ExternalizedContent

  /** Per-tool-call output written by the framework when a tool's
    * emission exceeds [[sigil.Sigil.contentExternalizationThreshold]].
    * Carries `expiresAt` so the [[sigil.maintenance.StoredFileExpirationSweep]]
    * task reclaims the storage after the configured retention. */
  case ToolOutput
}
