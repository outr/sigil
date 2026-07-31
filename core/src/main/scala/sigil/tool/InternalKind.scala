package sigil.tool

/**
 * Kind for framework-internal sentinel tools that are never
 * advertised to a model or surfaced through discovery
 * ([[sigil.tool.core.UnknownTool]]). Not discoverable, so no
 * keyword surface is required.
 */
case object InternalKind extends ToolKind {
  override def value: String = "internal"
  override def discoverable: Boolean = false
}
