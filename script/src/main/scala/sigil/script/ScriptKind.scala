package sigil.script

import sigil.tool.ToolKind

/**
 * [[ToolKind]] discriminator for [[ScriptTool]] records.
 * `sigil-script` opt-in module ships this; `ScriptSigil` registers
 * it in `toolKindRegistrations` so wire round-trips work without
 * apps wiring it manually.
 */
case object ScriptKind extends ToolKind {
  override def value: String = "script"
}
