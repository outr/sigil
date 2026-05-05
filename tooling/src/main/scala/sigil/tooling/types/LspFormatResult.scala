package sigil.tooling.types

import fabric.rw.*

/** Format-result shape shared by `lsp_format` and `lsp_format_range`.
  * `editsApplied` is the number of text edits the server returned and
  * the framework applied to disk; `0` means the server reported the
  * file already matches the formatter. */
case class LspFormatResult(filePath: String, editsApplied: Int) derives RW
