package sigil.tooling.types

import fabric.rw.*

/** Typed [[sigil.tool.ToolOutput]] for the LSP navigation tools that
  * resolve a position to a set of source locations — `lsp_goto_definition`,
  * `lsp_type_definition`, `lsp_implementation`.
  *
  * Empty `locations` means the server found nothing at that position —
  * a normal answer, distinct from a server error. */
case class LspLocationsResult(locations: List[LspLocation])
  extends sigil.tool.ToolOutput derives RW
