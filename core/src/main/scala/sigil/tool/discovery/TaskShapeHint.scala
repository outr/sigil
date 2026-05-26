package sigil.tool.discovery

import fabric.rw.*
import sigil.tool.ToolName

/**
 * Composition-derived hint surfaced alongside [[CapabilityMatch]]es
 * on [[sigil.tool.core.FindCapabilityOutput]]. The framework reads
 * the SET of matches plus the original query and, for a small
 * catalogue of recognised task shapes, recommends the primitive that
 * fits the shape — even when BM25 ranking puts a different tool at
 * the top.
 *
 *   - `shape` — stable discriminator the agent / UI can pattern-match
 *     on (e.g. `"multi_file_transformation"`,
 *     `"semantic_navigation"`).
 *   - `recommended` — the tool the framework thinks fits this shape
 *     best; ALWAYS one of the names already in the result's `matches`
 *     list so the agent's wire roster carries it.
 *   - `context` — human-readable rationale rendered into the agent's
 *     prompt under the matches.
 *
 * Hint synthesis lives in the framework
 * ([[sigil.tool.discovery.TaskShapeHints]]), not in any individual
 * tool's metadata — the signal is the COMPOSITION of the result set,
 * which no single tool can self-describe.
 */
case class TaskShapeHint(shape: String,
                         recommended: ToolName,
                         context: String) derives RW
