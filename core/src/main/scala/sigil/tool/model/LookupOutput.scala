package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.util.LookupTool]]. The lookup
 * surface is heterogeneous — a Memory / Information / Skill — so
 * the typed wrapper keeps the matched record's full JSON in
 * `payload` and lets the caller deserialize against whichever shape
 * matches `capabilityType`. Three states:
 *
 *   - `Found(capabilityType, name, payload)` — record found; `payload`
 *     is the full record as fabric JSON, ready to deserialize via
 *     the corresponding RW.
 *   - `NotFound(capabilityType, name)` — capabilityType + name
 *     resolved cleanly but no record matched.
 *   - `NotRetrievable(capabilityType, name, hint)` — the requested
 *     capabilityType doesn't have a retrieval surface
 *     (`Tool` / `Mode`); `hint` describes the right action.
 */
enum LookupOutput derives RW {
  case Found(capabilityType: String, name: String, payload: fabric.Json)
  case NotFound(capabilityType: String, name: String)
  case NotRetrievable(capabilityType: String, name: String, hint: String)
}
