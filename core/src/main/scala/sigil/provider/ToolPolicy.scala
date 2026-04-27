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
 * The six cases cover the useful combinations. Framework essentials
 * (`respond`, `no_response`, `change_mode`, `stop`) are always in the
 * roster; `find_capability` is in the roster unless the contributor is
 * [[None]].
 */
enum ToolPolicy derives RW {
  /** Contributor has no opinion on tools. Baseline participant roster
    * and full discovery catalog apply unchanged. */
  case Standard

  /** No tools beyond framework essentials — `find_capability` is also
    * suppressed. Agent is locked to its training for this contributor. */
  case None

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

  /** The tools listed on this policy. `Standard` and `None` have none. */
  def listed: List[ToolName] = this match {
    case Standard | None => Nil
    case Active(n)       => n
    case Discoverable(n) => n
    case Exclusive(n)    => n
    case Scoped(n)       => n
  }
}
