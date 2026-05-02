package sigil.provider

import fabric.rw.*
import sigil.tool.ToolName

/**
 * Policy describing how a [[Mode]] (or any other tool-roster contributor,
 * such as `Role`) shapes an agent's effective tool list on a turn.
 *
 * Two orthogonal dimensions control tool availability:
 *   - **Roster**: which tools the agent sees in its effective tool list
 *     on the turn.
 *   - **Discovery**: the pool `find_capability` can search through.
 *
 * The seven cases cover the useful combinations. Framework essentials
 * (`respond`, `no_response`, `change_mode`, `stop`) are in the roster
 * by default; `find_capability` is in the roster unless the contributor
 * is [[None]]. [[PureDiscovery]] strips the respond family + no_response
 * from the roster so every reply path goes through `find_capability`.
 */
enum ToolPolicy derives RW {
  /** Contributor has no opinion on tools. Baseline participant roster
    * and full discovery catalog apply unchanged. */
  case Standard

  /** No tools beyond framework essentials — `find_capability` is also
    * suppressed. Agent is locked to its training for this contributor. */
  case None

  /** Roster = `find_capability` + `stop` + the agent's baseline tools
    * (and any `Active` extras from other contributors). The respond
    * family (`respond`, `respond_options`, `respond_field`,
    * `respond_failure`) and `no_response` are stripped, forcing every
    * reply to flow through `find_capability` first.
    *
    * Right for small / quantised models that lock onto the `respond`
    * tool when it sits in the immediate roster. Adds one round-trip
    * per chat turn; large models don't need it. */
  case PureDiscovery

  /** `names` are added to the roster while this contributor is active. */
  case Active(names: List[ToolName])

  /** `names` are only visible to `find_capability` while this contributor
    * is active — hidden from the discovery catalog of other contributors.
    * Not added to the immediate roster; agent must discover explicitly. */
  case Discoverable(names: List[ToolName])

  /** Roster = framework essentials ∪ `names`. Baseline participant tools
    * are suppressed; discovery is restricted to `names`. */
  case Exclusive(names: List[ToolName])

  /** Baseline roster unchanged; `find_capability` discovery scope restricted
    * to `names`. Useful for "keep your normal tools, but only find
    * contributor-relevant new ones." */
  case Scoped(names: List[ToolName])

  /** The tools listed on this policy. `Standard`, `None`, and `PureDiscovery`
    * have none. */
  def listed: List[ToolName] = this match {
    case Standard | None | PureDiscovery => Nil
    case Active(n)                       => n
    case Discoverable(n)                 => n
    case Exclusive(n)                    => n
    case Scoped(n)                       => n
  }
}
