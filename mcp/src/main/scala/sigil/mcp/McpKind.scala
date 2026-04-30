package sigil.mcp

import sigil.tool.ToolKind

/**
 * [[ToolKind]] discriminator for [[McpTool]] records — tools surfaced
 * by an MCP server registered against [[McpSigil]]. Apps build "MCP
 * tools" UIs by filtering [[sigil.signal.RequestToolList]] to
 * `kinds = Some(Set(McpKind))`.
 */
case object McpKind extends ToolKind {
  override def value: String = "mcp"
}
