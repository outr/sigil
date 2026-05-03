package sigil.conversation

import fabric.rw.*

/**
 * Origin metadata for a [[ContextMemory]] — purely descriptive (the
 * framework doesn't key any behaviour off this value; rendering policy
 * lives on [[ContextMemory.pinned]]).
 *
 *   - `Compression` — extracted by a summarization / compression pass
 *                     or by the per-turn extractor
 *   - `Explicit`    — written deliberately by the agent via a memory tool
 *   - `UserInput`   — authored or edited directly by a human via the app UI
 *
 * "Always loaded vs topical" is not an origin axis — it's a rendering
 * policy axis, captured by [[ContextMemory.pinned]]. A memory authored
 * via the agent (`Explicit`) can be either pinned or not; the source
 * doesn't determine that.
 */
enum MemorySource derives RW {
  case Compression
  case Explicit
  case UserInput
}
