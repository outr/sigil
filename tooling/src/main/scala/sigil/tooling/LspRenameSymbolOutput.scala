package sigil.tooling

import fabric.rw.*
import sigil.tooling.types.LspWorkspaceSymbol

/**
 * Output for [[LspRenameSymbolTool]] — describes the outcome of
 * the high-level semantic rename.
 *
 * Three terminal shapes:
 *
 *   - [[Renamed]]    — exactly one symbol matched, the underlying
 *                      `lsp_rename` applied; `filesChanged` reports
 *                      how many files the workspace edit covered.
 *   - [[Ambiguous]]  — multiple symbols matched the query; the tool
 *                      returns the candidates so the agent can
 *                      disambiguate via `kindHint` or a more
 *                      specific `symbolName`.
 *   - [[NotFound]]   — no symbol matched the query.
 *   - [[Failed]]     — the rename was attempted but the underlying
 *                      `lsp_rename` reported no edits / partial
 *                      failure.
 */
enum LspRenameSymbolOutput derives RW {
  case Renamed(symbolName: String, newName: String, filesChanged: Int)
  case Ambiguous(symbolName: String, matches: List[LspWorkspaceSymbol])
  case NotFound(symbolName: String, reason: String)
  case Failed(symbolName: String, reason: String)
}
