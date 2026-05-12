# ❌ #144 — Curator doesn't load persisted `ContextSummary` records; no path for "compress once at import, recall many times" on bulk-imported conversations

**Where:**
- `core/src/main/scala/sigil/conversation/compression/StandardContextCurator.scala:86-136` — `curate`
- `core/src/main/scala/sigil/Sigil.scala:3656` — `summariesFor` exists but the curator never reads it
- `core/src/main/scala/sigil/Sigil.scala:2899` — `framesFor` is unbounded

**What's wrong:**

The curator is built for an architecture that doesn't scale to bulk imports. The current `curate` flow is:

1. Load ALL frames for the conversation (`sigil.framesFor` — 48,000+ rows for a Claude Code session import)
2. Run block extraction over all of them
3. Build a transcript from all of them
4. Hand the whole transcript to the compressor each turn

This means **every per-turn curate re-summarizes the entire conversation history from scratch**. On a 48K-frame import:

- Block extraction takes 1-3 minutes (filed as #142)
- Compression produces an 18 MB request body that no provider accepts (filed as #143)
- The compressor's result, even if it succeeded, would be a one-shot summary recomputed on every turn — wasted work

The framework already has `ContextSummary` as a first-class concept. `Sigil.persistSummary` writes them, `Sigil.summariesFor(conversationId)` reads them back. But **`StandardContextCurator.curate` never calls `summariesFor`**. It only knows about summaries it produces itself, inside the current turn's stage-3 shed. So a summary persisted earlier (whether at import time, on a previous turn, or by an explicit user-driven "compress now" tool) never makes it into the next turn's `TurnInput`.

**Suggested fix:**

The right architecture for bulk-imported conversations is "compress once, recall many":

1. **Curator pulls persisted summaries.** Add to the `curate` for-comprehension:
   ```scala
   persistedSummaries <- sigil.summariesFor(conversationId).map(_.toVector)
   ```
   Include them in the tentative `TurnInput.summaries` BEFORE the budget gate runs. The budget gate then either keeps them (cheap relative to raw frames) or sheds them with normal stage-3 logic if even the summaries are too large. The curator's existing budget plumbing handles them correctly — the only missing piece is loading them from disk.

2. **Bound `framesFor` (or add `framesForRecent(N)`).** Either:
   - Add a `maxFramesPerTurn: Int = ?` knob on `StandardContextCurator` that takes the most-recent N from `framesFor`'s result, or
   - Add a `framesForRecent(conversationId, limit)` query method on `Sigil` that LightDB-shaped apps can implement as a sorted-descending take rather than full-collection-scan-then-sort.

   Today even reading 48,000 frames into a Vector before filtering is a significant cost (the events table is scanned linearly). Most turns don't need to see anything before the last hour or two.

3. **Add a `compressOnImport` hook.** A new `Sigil` method:
   ```scala
   def compressOnImport(conversationId: Id[Conversation], framesAdded: Long): Task[Unit] = Task.unit
   ```
   `publishHistorical` invokes it after bulk-write completes. Apps that wire a compressor override this to fire a one-time hierarchical compression job — chunk the imported frames, summarize each chunk, persist as `ContextSummary` records, optionally merge into a top-level "epoch" summary. The default is a no-op so apps that don't need it pay nothing.

4. **Add a hierarchical compression utility.** `MemoryContextCompressor` today produces one flat summary. Add `MemoryContextCompressor.compressHierarchical(frames, depth: Int = 2)` that recursively chunks → summarizes → re-summarizes until the result fits comfortably under a chunk-size cap. With proper byte ceilings (#143), this handles arbitrarily large inputs by treeing them — 48K frames → 96 chunks of 500 → 96 chunk summaries → 8 epoch summaries → 1 top summary, each step under any provider's body cap.

**Severity:** Critical for bulk-import use cases. The framework's `load_claude_state` tool exists specifically to ingest historical conversations, and the framework's curator can't handle the result. Today the workaround is "limit framesFor on the Sage side and accept that older history isn't represented in the per-turn prompt" — which loses the imported content's value. The proper fix (precomputed summaries flowing through to per-turn curate) keeps the imported history accessible without re-paying the compression cost every turn.

**Sage workaround in place:** Sage now caps `framesFor` to the most-recent N frames (see `Sage.framesFor` override) so per-turn curates fit any model's context. The older imported frames remain in the DB and surface via memory retrieval when the per-turn extractor has saved relevant facts, but they're not in the agent's direct prompt. This is a bridge fix, not a solution — the agent has no direct visibility into the imported history's content until the hierarchical-summary path lands.
