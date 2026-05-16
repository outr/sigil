package sigil.tool.discovery

import fabric.rw.*

/**
 * Unified discovery result from `find_capability`.
 *
 * Carries enough metadata for the agent to decide whether and how to
 * use the matched capability:
 *   - `name` / `description` — what it is, identical to the tool /
 *     mode / skill's own self-description.
 *   - `capabilityType` — discriminator for what kind it is, so the
 *     agent reads the entry differently per kind.
 *   - `score` — relevance score from the finder; higher is better.
 *     Used purely for ranking; not surfaced to the model.
 *   - `status` — availability semantics. `Ready` for tools (call by
 *     name); `RequiresSetup(hint)` for modes (the hint carries the
 *     `change_mode("…")` invocation needed to unlock the matching
 *     tool roster).
 *   - `paginate` — `Some(true)` when the matched tool emits a paged
 *     `JsonPagedResult` (the agent must walk pages with `next_page`
 *     to consume the full output); `Some(false)` for single-shot
 *     tools whose result lands in one [[sigil.event.ToolResults]]
 *     event. `None` for non-tool capability kinds (modes, skills,
 *     memories) where pagination is not a meaningful concept.
 *
 * Mirrors Scalagentic's `CapabilityMatch` shape (the framework's
 * predecessor) and leaves room to grow categories (`McpServer`,
 * `Agent`, `Marketplace`, …) without changing the result type.
 */
case class CapabilityMatch(name: String,
                           description: String,
                           capabilityType: CapabilityType,
                           score: Double,
                           status: CapabilityStatus,
                           paginate: Option[Boolean] = None) derives RW
