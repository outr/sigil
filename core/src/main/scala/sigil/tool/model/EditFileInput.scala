package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `edit_file` — find/replace within a single file.
 * `replaceAll = false` (default) replaces only the first occurrence;
 * `true` replaces every occurrence.
 */
case class EditFileInput(filePath: String,
                         oldString: String,
                         newString: String,
                         replaceAll: Boolean = false) extends ToolInput derives RW
