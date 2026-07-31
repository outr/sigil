package sigil.tool.consult

import sigil.tool.ToolKind

/**
 * Kind for one-shot consult tools the framework invokes directly
 * (memory extraction, summarization, topic classification) rather
 * than advertising through discovery. Not discoverable, so no
 * keyword surface is required.
 */
case object ConsultKind extends ToolKind {
  override def value: String = "consult"
  override def discoverable: Boolean = false
}
