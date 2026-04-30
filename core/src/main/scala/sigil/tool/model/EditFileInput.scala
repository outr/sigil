package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `edit_file` — find/replace within a single file.
 * `replaceAll = false` (default) replaces only the first occurrence;
 * `true` replaces every occurrence.
 *
 * `expectedHash` (optional) enables safe-edit: the tool reads the
 * file, applies the replacement, then commits only if the file's
 * SHA-256 hash still matches `expectedHash`. On mismatch the tool
 * returns a `stale` result with the file's current contents so the
 * agent can re-evaluate the edit against the new state. Without it,
 * the edit commits unconditionally.
 */
case class EditFileInput(filePath: String,
                         oldString: String,
                         newString: String,
                         replaceAll: Boolean = false,
                         expectedHash: Option[String] = None) extends ToolInput derives RW
