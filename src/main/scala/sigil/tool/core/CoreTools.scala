package sigil.tool.core

import fabric.rw.*
import sigil.Sigil
import sigil.tool.{Tool, ToolInput}
import sigil.tool.model.{ChangeModeInput, NoResponseInput, RespondInput}

/**
 * The canonical set of framework-level tools every sigil agent should have
 * access to. Covers the terminal action grammar (`respond`, `no_response`),
 * mode transitions (`change_mode`), and capability discovery
 * (`find_capability`).
 *
 * Constructed per-instance because `FindCapabilityTool` holds a reference
 * to the app's `Sigil` (for tool discovery via `sigil.findTools`). Apps
 * include `all` in their [[sigil.provider.ProviderRequest.tools]]; input
 * RWs are registered automatically by `Sigil.instance.sync()`.
 */
case class CoreTools(sigil: Sigil) {

  /**
   * The tool instances — pass to `ProviderRequest.tools`.
   */
  val all: Vector[Tool[? <: ToolInput]] =
    Vector(RespondTool, ChangeModeTool, NoResponseTool, FindCapabilityTool(sigil))
}

object CoreTools {

  /**
   * The ToolInput RWs for polymorphic registration. Sigil registers these
   * automatically during `instance.sync()`; apps don't need to touch this.
   */
  val inputRWs: List[RW[? <: ToolInput]] =
    List(
      summon[RW[RespondInput]],
      summon[RW[ChangeModeInput]],
      summon[RW[NoResponseInput]],
      summon[RW[FindCapabilityInput]]
    )

  /**
   * Canonical names of the framework-supplied core tools. Agent authors use
   * this as the baseline `toolNames` on a persisted [[sigil.participant.AgentParticipant]]
   * (e.g. `toolNames = CoreTools.coreToolNames ++ List("my_app_tool")`).
   * The dispatcher resolves each name to a live `Tool` instance through
   * [[sigil.tool.ToolFinder.byName]] at call time.
   */
  val coreToolNames: List[String] = List(
    RespondTool.schema.name,
    ChangeModeTool.schema.name,
    NoResponseTool.schema.name,
    "find_capability"
  )
}
