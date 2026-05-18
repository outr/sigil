package sigil.tooling.dispatch

import fabric.Json
import fabric.rw.*

/**
 * Optional LLM step in a [[WorkerPipeline]]. Each worker item is
 * substituted into the `prompt` template (and `systemPrompt`)
 * via `{{item}}` (the raw JSON) and `{{item.<path>}}` (dot-path
 * accessor against the item's JSON shape — e.g. `{{item.filePath}}`,
 * `{{item.range.start.line}}`).
 *
 * When `outputSchema` is set the LLM call is grammar-constrained to
 * a tool-call returning a value of that shape — the returned JSON
 * lands as the script step's `input` binding. When unset the LLM's
 * free-form text response is passed through as a single-string
 * payload (`{"text": "<response>"}`).
 *
 *   - `systemPrompt` — defaults to empty; populates the
 *     [[sigil.provider.OneShotRequest.systemPrompt]] field.
 *   - `prompt`       — populates `userPrompt`. Must reference
 *                       `{{item}}` (or a path under it) at least once.
 *   - `outputSchema` — optional fabric definition the LLM response
 *                       must conform to. When set, the dispatched call
 *                       routes through `tool_choice = "required"` so
 *                       the response is structured.
 *   - `maxTokens`    — optional cap on the per-item response; uses
 *                       the provider's default when unset.
 *   - `temperature`  — optional sampling temperature override.
 */
case class LlmStep(prompt: String,
                   systemPrompt: String = "",
                   outputSchema: Option[Json] = None,
                   maxTokens: Option[Int] = None,
                   temperature: Option[Double] = None) derives RW
