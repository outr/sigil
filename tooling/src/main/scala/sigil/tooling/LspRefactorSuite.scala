package sigil.tooling

import sigil.tool.ToolName

/**
 * Tool-name listing for the LSP refactoring + navigation surface
 * that an agent typically wants in scope during coding work. Apps
 * that wire `sigil-tooling` and use a coding-shaped Mode compose
 * this into their Mode's `ToolPolicy` so the agent has the refactor
 * suite available without needing to discover each tool individually.
 *
 * Example wiring (in app code, where the Mode is defined):
 *
 * {{{
 *   object MyCodingMode extends Mode {
 *     override val name = "coding"
 *     override def tools: ToolPolicy =
 *       ToolPolicy.Active(LspRefactorSuite.toolNames)
 *   }
 * }}}
 *
 * The framework's existing `find_capability` ranker continues to
 * surface these tools for refactor-shaped queries via their keyword
 * sets; this listing is for the "always available during coding
 * work" path.
 */
object LspRefactorSuite {

  /**
   * The refactor / navigation / inspection tools that compose well
   * during a typical coding session.
   */
  val toolNames: List[ToolName] = List(
    ToolName("lsp_rename"),
    ToolName("lsp_prepare_rename"),
    ToolName("lsp_code_action"),
    ToolName("lsp_apply_code_action"),
    ToolName("lsp_goto_definition"),
    ToolName("lsp_type_definition"),
    ToolName("lsp_find_references"),
    ToolName("lsp_implementation"),
    ToolName("lsp_hover"),
    ToolName("lsp_signature_help"),
    ToolName("lsp_completion"),
    ToolName("lsp_format"),
    ToolName("lsp_format_range"),
    ToolName("lsp_diagnostics"),
    ToolName("lsp_pull_diagnostics"),
    ToolName("lsp_document_symbols"),
    ToolName("lsp_workspace_symbols"),
    ToolName("lsp_code_lens"),
    ToolName("lsp_inlay_hints"),
    ToolName("lsp_folding_range"),
    ToolName("lsp_selection_range"),
    ToolName("lsp_document_link")
  )
}
