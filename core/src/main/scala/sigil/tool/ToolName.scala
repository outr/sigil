package sigil.tool

import fabric.rw.*

import scala.compiletime.constValue
import scala.compiletime.ops.string.Matches

/**
 * Typed wrapper around a tool's name. Used everywhere a tool is
 * referenced by identity: [[Tool.schema]], [[ToolFinder.byName]],
 * [[sigil.event.ToolInvoke.toolName]], [[sigil.participant.AgentParticipant.toolNames]],
 * and the per-participant `recentToolInvocations` / `suggestedTools`
 * projections.
 *
 * Three construction tiers:
 *
 *   - **Literals** — `ToolName("grep")` validates the authoring
 *     grammar `[a-z][a-z0-9_]{0,63}` at compile time; a bad literal
 *     is a compile error carrying the grammar.
 *   - **Dynamic names** — [[ToolName.parse]] validates against the
 *     wider provider grammar `[a-zA-Z0-9_-]{1,64}` (what OpenAI-shape
 *     APIs accept) so runtime-built names (MCP prefixing) that are
 *     legal on the wire aren't rejected for style.
 *   - **Framework synthetics** — [[ToolName.internal]] constructs
 *     underscore-prefixed diagnostic names (`_plan`,
 *     `_stall_detected`) and round-trips names already persisted on
 *     event rows; framework-internal only.
 *
 * Serializes as a bare string — identical wire shape to the previous
 * AnyVal case class.
 */
opaque type ToolName = String

object ToolName {

  /**
   * Authoring grammar for literal names — lowercase snake_case.
   */
  inline val LiteralGrammar = "[a-z][a-z0-9_]{0,63}"

  /**
   * Wire grammar for dynamic names — what providers accept.
   */
  val DynamicGrammar: String = "[a-zA-Z0-9_-]{1,64}"

  private val dynamicPattern = DynamicGrammar.r

  /**
   * Compile-time-validated literal construction. A non-matching
   * literal (or a non-constant argument) fails compilation.
   */
  inline def apply[S <: String & Singleton](inline value: S): ToolName =
    inline if constValue[Matches[S, LiteralGrammar.type]] then value
    else
      scala.compiletime.error("Invalid tool name literal '" + value +
        "' — grammar is [a-z][a-z0-9_]{0,63} (lowercase snake_case, max 64 chars). " +
        "Runtime-built names go through ToolName.parse.")

  /**
   * Runtime construction for dynamically-built names (MCP server
   * prefixing, user-created tools). Validates the provider grammar.
   */
  def parse(value: String): Either[String, ToolName] =
    if (dynamicPattern.matches(value)) Right(value)
    else Left(s"Invalid tool name '$value' — providers accept $DynamicGrammar")

  /**
   * Framework-internal constructor: underscore-prefixed synthetic
   * diagnostic names and round-trips of names already validated at
   * their original construction (event rows, wire calls).
   */
  private[sigil] def internal(value: String): ToolName = value

  extension (name: ToolName) {
    def value: String = name
  }

  given rw: RW[ToolName] = RW.string[ToolName](asString = n => n, fromString = s => s, className = Some("sigil.tool.ToolName"))
}
