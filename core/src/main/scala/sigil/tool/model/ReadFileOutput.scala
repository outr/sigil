package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.fs.ReadFileTool]]. `hash` is the
 * full-file hash for use as `expectedHash` on a subsequent
 * write/edit; only populated when the read was unwindowed (the
 * hash represents bytes the agent didn't see when partial-read).
 * `linesRead <= totalLines`.
 */
case class ReadFileOutput(content: String,
                          totalLines: Int,
                          linesRead: Int,
                          hash: Option[String] = None)
  extends sigil.tool.ToolOutput derives RW {

  /**
   * Sigil #404 — present the file content to the model as clean text so a
   * line copied out as an `edit_file` `oldString` anchor equals the bytes in
   * the file. The JSON envelope escaped `"` → `\"` and `/` → `\/`, breaking
   * every anchor sourced from a `"`- or `/`-bearing line. The read metadata
   * rides as a plain trailer (the `hash` is the string the agent echoes into
   * `edit_file.expectedHash`, so it stays model-facing).
   */
  override def modelText: Option[String] = {
    val hashPart = hash.map(h => s" · hash $h").getOrElse("")
    Some(s"$content\n\n[read_file: $linesRead of $totalLines lines$hashPart]")
  }
}
