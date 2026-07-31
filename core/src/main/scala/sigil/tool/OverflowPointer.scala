package sigil.tool

import fabric.rw.*

/**
 * Marker that a tool result's rendered form exceeded
 * [[sigil.Sigil.inlineContentThreshold]] and was bounded for the
 * model-facing channel. The typed output is preserved on the invoke;
 * the bounded head lives on `summary`.
 *
 * `path` is the file the full rendered output was written to under
 * the conversation's [[sigil.tool.fs.FileSystemContext]] — the agent
 * recovers the rest with `grep` / `read_file`. `None` when no
 * workspace was bound (the result was truncated inline instead).
 */
case class OverflowPointer(path: Option[String], bytes: Long, lines: Int) derives RW
