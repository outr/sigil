package sigil.tool.core

import fabric.rw.*
import sigil.tool.{Tool, ToolInput}
import sigil.tool.model.{ChangeModeInput, NoResponseInput, RespondInput}

/**
 * The canonical set of framework-level tools every sigil agent should have
 * access to. Covers the terminal action grammar (`respond`, `no_response`),
 * mode transitions (`change_mode`), and capability discovery
 * (`find_capability`).
 *
 * Constructed per-instance because `FindCapabilityTool` holds a reference to
 * the app-provided [[ToolManager]]. Apps include `all` in their
 * [[sigil.provider.ProviderRequest.tools]] and register `inputRWs` through
 * their `Sigil` implementation.
 */
case class CoreTools(toolManager: ToolManager) {

  /**
   * The tool instances — pass to `ProviderRequest.tools`.
   */
  val all: Vector[Tool[? <: ToolInput]] =
    Vector(RespondTool, ChangeModeTool, NoResponseTool, FindCapabilityTool(toolManager))
}

object CoreTools {

  /**
   * The ToolInput RWs for polymorphic registration. Include these in a Sigil
   * implementation's `toolInputs` list so serialization of persisted tool
   * inputs round-trips correctly. Static because the RWs don't depend on a
   * `ToolManager` instance.
   */
  val inputRWs: List[RW[? <: ToolInput]] =
    List(
      summon[RW[RespondInput]],
      summon[RW[ChangeModeInput]],
      summon[RW[NoResponseInput]],
      summon[RW[FindCapabilityInput]]
    )
}
