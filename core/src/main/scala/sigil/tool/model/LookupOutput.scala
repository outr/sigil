package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.util.LookupTool]]. The lookup
 * surface is heterogeneous — a Memory / Information / Skill — so
 * the typed wrapper keeps the matched record's full JSON in
 * `payload` and lets the caller deserialize against whichever shape
 * matches `capabilityType`. Three states:
 *
 *   - `Found(capabilityType, name, payload, chunk)` — record found;
 *     `payload` is the record as fabric JSON, ready to deserialize via
 *     the corresponding RW. `chunk` is set (sigil #389) when the record
 *     was too large for the inline cap and its dominant text field was
 *     windowed — call `lookup` again with `offset = chunk.nextOffset` for
 *     the next chunk.
 *   - `NotFound(capabilityType, name)` — capabilityType + name
 *     resolved cleanly but no record matched.
 *   - `NotRetrievable(capabilityType, name, hint)` — the requested
 *     capabilityType doesn't have a retrieval surface
 *     (`Tool` / `Mode`); `hint` describes the right action.
 */
enum LookupOutput extends sigil.tool.ToolOutput derives RW {
  case Found(capabilityType: String, name: String, payload: fabric.Json, chunk: Option[LookupChunk] = None)
  case NotFound(capabilityType: String, name: String)
  case NotRetrievable(capabilityType: String, name: String, hint: String)
}
