package sigil.tool

import fabric.rw.*
import sigil.tool.model.{ChangeModeInput, NoResponseInput, RespondInput}

/**
 * The canonical set of framework-level tools every sigil agent should have
 * access to. Covers the terminal action grammar (`respond`, `no_response`)
 * plus mode transitions (`change_mode`).
 *
 * Applications include these in their [[sigil.provider.ProviderRequest.tools]]
 * and register the corresponding input RWs through their `Sigil` implementation.
 */
object CoreTools {

  /**
   * The tool instances — pass to `ProviderRequest.tools`.
   */
  val all: Vector[Tool[? <: ToolInput]] = Vector(RespondTool, ChangeModeTool, NoResponseTool)

  /**
   * The ToolInput RWs for polymorphic registration. Include these in a Sigil
   * implementation's `toolInputs` list so serialization of persisted tool
   * inputs round-trips correctly.
   */
  val inputRWs: List[RW[? <: ToolInput]] =
    List(summon[RW[RespondInput]], summon[RW[ChangeModeInput]], summon[RW[NoResponseInput]])
}
