package sigil.tooling.refactor

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[LspRenameSymbolTool]] — a high-level semantic rename
 * by name, wrapping the existing `lsp_workspace_symbols → extract
 * position → lsp_rename` dance into one call.
 *
 * @param languageId   the LSP server discriminator (e.g. "scala").
 * @param projectRoot  the project root path used to resolve the
 *                     workspace.
 * @param symbolName   the symbol to rename (exact match unless
 *                     [[fuzzy]] is true).
 * @param newName      the replacement identifier.
 * @param fuzzy        when true, accept substring / fuzzy matches
 *                     from `lsp_workspace_symbols`. Default false
 *                     so the tool refuses an over-broad match
 *                     accidentally.
 * @param kindHint     optional kind filter when multiple symbols
 *                     share the name (e.g. "class", "method").
 */
case class LspRenameSymbolInput(languageId: String,
                                projectRoot: String,
                                symbolName: String,
                                newName: String,
                                fuzzy: Boolean = false,
                                kindHint: Option[String] = None)
  extends ToolInput derives RW
