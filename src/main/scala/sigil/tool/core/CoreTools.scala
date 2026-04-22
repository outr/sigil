package sigil.tool.core

import fabric.rw.*
import sigil.tool.{Tool, ToolInput}
import sigil.tool.model.{ChangeModeInput, NoResponseInput, RespondInput, StopInput}

/**
 * The canonical set of framework-level tools every sigil agent should
 * have access to. Covers the terminal action grammar (`respond`,
 * `no_response`), mode transitions (`change_mode`), capability discovery
 * (`find_capability`), and turn halting (`stop`).
 *
 * Tools that exist in the framework but are NOT in the default roster —
 * `sleep`, `lookup_information`, any other utility — live in
 * [[sigil.tool.util]]. Apps opt those in explicitly by including their
 * names in an agent's `toolNames` list.
 *
 * All core tools are singleton objects — they take their `Sigil` from
 * `TurnContext.sigil` at call time, so there's nothing instance-specific
 * to construct. Apps include [[CoreTools.all]] in their
 * [[sigil.provider.ProviderRequest.tools]]; the input RWs are registered
 * automatically by `Sigil.instance.sync()`.
 */
object CoreTools {

  /**
   * The tool instances — pass to `ProviderRequest.tools`.
   */
  val all: Vector[Tool[? <: ToolInput]] =
    Vector(RespondTool, ChangeModeTool, NoResponseTool, FindCapabilityTool, StopTool)

  /**
   * The ToolInput RWs for polymorphic registration. Sigil registers these
   * automatically during `instance.sync()`; apps don't need to touch this.
   */
  val inputRWs: List[RW[? <: ToolInput]] =
    List(
      summon[RW[RespondInput]],
      summon[RW[ChangeModeInput]],
      summon[RW[NoResponseInput]],
      summon[RW[FindCapabilityInput]],
      summon[RW[StopInput]]
    )

  /**
   * Canonical names of the framework-supplied core tools. Agent authors use
   * this as the baseline `toolNames` on a persisted
   * [[sigil.participant.AgentParticipant]] (e.g. `toolNames =
   * CoreTools.coreToolNames :+ MyAppTool.schema.name`). The dispatcher
   * resolves each name to a live `Tool` instance through
   * [[sigil.tool.ToolFinder.byName]] at call time.
   */
  val coreToolNames: List[sigil.tool.ToolName] = all.map(_.schema.name).toList
}
