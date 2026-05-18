package sigil.conversation

import fabric.rw.*
import lightdb.time.Timestamp
import sigil.tool.ToolName

/**
 * One entry in the per-agent-loop `find_capability` cache held on
 * [[sigil.TurnContext]] — the tool matches a `find_capability` query
 * returned, plus the first / most-recent time the query was issued
 * within the current agent loop.
 *
 * Lifetime is bounded by a single agent loop: the cache is created
 * fresh when the loop starts and discarded when the loop terminates.
 * That matches what the cache exists FOR — avoiding redundant
 * re-discovery within an iteration sequence working on one task —
 * without surfacing tools from prior, unrelated turns into the
 * current turn's system prompt.
 */
case class DiscoveredCapability(matches: List[ToolName],
                                firstSeen: Timestamp,
                                lastSeen: Timestamp) derives RW
