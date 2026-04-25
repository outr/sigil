package sigil.conversation

import fabric.rw.*

/**
 * Origin metadata for a [[ContextMemory]]. Purely descriptive — the
 * framework doesn't key any behavior off this value. Apps that want
 * source-specific retention policy (e.g. "never prune Critical") implement
 * it inside their curator.
 *
 *   - `Critical`    — a directive that must stay visible to the model
 *                     (e.g. "always reply in JSON")
 *   - `Compression` — extracted by a summarization / compression pass
 *                     or by the per-turn extractor
 *   - `Explicit`    — written deliberately by the agent via a memory tool
 *   - `UserInput`   — authored or edited directly by a human via the app UI
 */
enum MemorySource derives RW {
  case Critical
  case Compression
  case Explicit
  case UserInput
}
