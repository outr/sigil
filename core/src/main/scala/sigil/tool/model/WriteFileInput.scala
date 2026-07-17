package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `write_file`. When `expectedHash` is set, the write
 * uses safe-edit semantics: it commits only if the file's current
 * SHA-256 hash matches the supplied value, otherwise the tool
 * returns a `stale` result with the freshest contents so the agent
 * can re-attempt against the new state. Without `expectedHash` the
 * write is unconditional (last-writer-wins) — the legacy behavior
 * for single-agent flows that don't need staleness protection.
 *
 * `force` opts out of the destructive-overwrite guard, which otherwise
 * refuses to replace an existing non-empty file with content that is
 * self-evidently not a real rewrite (a tool-call / pagination
 * placeholder, a lone unresolved `{{var}}`, or a drastic collapse).
 * Set `force = true` for the rare intentional truncate-to-nothing case.
 */
case class WriteFileInput(path: String,
                          content: String,
                          expectedHash: Option[String] = None,
                          force: Boolean = false)
  extends ToolInput derives RW
