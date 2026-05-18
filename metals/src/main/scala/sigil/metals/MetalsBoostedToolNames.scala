package sigil.metals

import sigil.tool.ToolName

/**
 * Tool names pinned to a conversation when [[StartMetalsTool]]
 * succeeds (sigil bug #97). The conversation's
 * [[sigil.conversation.ConversationToolOverlay]] receives an
 * `Active(MetalsBoostedToolNames.all)` policy so subsequent turns
 * can call any of these directly without a `find_capability`
 * round-trip.
 *
 * Covers the three families that become available once Metals is
 * running for a workspace:
 *
 *   - **lsp_*** — generic LSP tools backed by the `LspServerConfig("scala", ...)`
 *     written by [[MetalsSigil.writeLspServerConfigForMetals]] (#88).
 *   - **bsp_*** — generic BSP tools that drive sbt / Bloop via the
 *     persisted [[sigil.tooling.BspBuildConfig]].
 *   - **metals_*** — the lifecycle / status surface (start, stop,
 *     metals_status) so the agent can introspect the subprocess.
 */
object MetalsBoostedToolNames {
  val lsp: List[ToolName] = List(
    "lsp_diagnostics",
    "lsp_pull_diagnostics",
    "lsp_goto_definition",
    "lsp_type_definition",
    "lsp_implementation",
    "lsp_find_references",
    "lsp_hover",
    "lsp_signature_help",
    "lsp_completion",
    "lsp_document_symbols",
    "lsp_workspace_symbols",
    "lsp_code_action",
    "lsp_apply_code_action",
    "lsp_format",
    "lsp_format_range",
    "lsp_rename",
    "lsp_prepare_rename",
    "lsp_did_change",
    "lsp_folding_range",
    "lsp_selection_range",
    "lsp_inlay_hints",
    "lsp_code_lens",
    "lsp_document_link"
  ).map(ToolName(_))

  val bsp: List[ToolName] = List(
    "bsp_compile",
    "bsp_test",
    "bsp_run",
    "bsp_clean",
    "bsp_reload",
    "bsp_list_targets",
    "bsp_sources",
    "bsp_inverse_sources",
    "bsp_dependency_sources",
    "bsp_dependency_modules",
    "bsp_resources",
    "bsp_output_paths",
    "bsp_scalac_options",
    "bsp_scala_test_classes",
    "bsp_scala_main_classes"
  ).map(ToolName(_))

  val metals: List[ToolName] = List(
    "start_metals",
    "stop_metals",
    "metals_status"
  ).map(ToolName(_))

  val all: List[ToolName] = lsp ++ bsp ++ metals

  /**
   * Source tag the overlay carries — apps remove the metals
   * overlay via this value (or pass it directly to
   * [[sigil.Sigil.removeConversationToolOverlay]]).
   */
  val OverlaySource: String = "start_metals"
}
