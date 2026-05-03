package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[ClassifyMemoryTool]] — the unified one-shot classifier
 * the framework runs at memory write time. Produces three orthogonal
 * decisions in a single LLM round-trip:
 *
 *   - `keywords`       — 5-10 retrieval-shaped tokens stored on
 *                        [[sigil.conversation.ContextMemory.keywords]]
 *                        and indexed via `searchText` for the lexical
 *                        leg of [[sigil.conversation.compression.StandardMemoryRetriever]].
 *   - `permanence`     — `"Once"` (topical retrieval surfaces when
 *                        relevant) | `"Always"` (pinned every turn).
 *                        Driven by imperative cues in the user
 *                        message that triggered the save.
 *   - `space`          — the `value` of the accessible [[sigil.SpaceId]]
 *                        the memory belongs in, OR the literal
 *                        `"ambiguous"` if the classifier can't pick
 *                        confidently.
 *   - `ambiguityReason`— explanation when `space == "ambiguous"`;
 *                        the agent uses this to ask the user.
 *
 * Unknown values fall back to safe defaults at the call site:
 * unrecognised `permanence` → `Once`; unrecognised `space` →
 * treated as ambiguous and the caller asks. Keep the prompt tight
 * — the model that powers this is typically small / fast.
 */
case class ClassifyMemoryInput(keywords: List[String],
                               permanence: String,
                               space: String,
                               ambiguityReason: Option[String] = None) extends ToolInput derives RW
