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
 */
case class WriteFileInput(filePath: String,
                          content: String,
                          expectedHash: Option[String] = None) extends ToolInput derives RW
