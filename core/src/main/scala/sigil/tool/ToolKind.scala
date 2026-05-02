package sigil.tool

import fabric.rw.PolyType

/**
 * Open PolyType discriminating tool subtypes for client-side
 * filtering. The framework ships [[BuiltinKind]] as the default;
 * opt-in modules add their own (`ScriptKind` in `sigil-script`,
 * `McpKind` in `sigil-mcp`); apps add app-specific kinds by
 * extending the trait + registering via
 * `Sigil.toolKindRegistrations`.
 *
 * Used by [[sigil.signal.RequestToolList]] / [[sigil.signal.ToolListSnapshot]]
 * so a UI can ask "show me just the script tools the user created"
 * without loading the full Tool record. Apps that don't need
 * filtering by kind don't override `Tool.kind` and every tool ends
 * up as `BuiltinKind` (matches the bag-of-tools default behavior).
 */
trait ToolKind {

  /** Stable string the wire layer uses on the polymorphic discriminator.
    * Must be unique across registered subtypes. */
  def value: String
}

object ToolKind extends PolyType[ToolKind]()(using scala.reflect.ClassTag(classOf[ToolKind]))

/** Default kind for framework-shipped tools (respond, change_mode,
  * find_capability, etc.). Apps' static catalog tools that don't
  * declare their own kind also fall into this bucket. */
case object BuiltinKind extends ToolKind {
  override def value: String = "builtin"
}
