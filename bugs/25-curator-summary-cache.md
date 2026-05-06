# ❌ #25 — Curator re-computes ContextSummary on every turn instead of reusing persisted ones

**Status:** perf concern, not correctness. Files alongside #23 / #24
which together cover the giant-history correctness story.

**Where:**
- `core/src/main/scala/sigil/conversation/compression/StandardContextCurator.scala:151`
  — Stage 3 calls `compressor.compress(...)` on every turn that
  needs to shed.
- `core/src/main/scala/sigil/conversation/ContextSummary.scala` —
  `ContextSummary` IS already a persisted `RecordDocument`. Each
  compress() call upserts a new record (per
  `Sigil.persistSummary`). The records persist; no one reads them
  back across turns.

**What's wrong:** the curator's Stage 3 calls `compressor.compress`
unconditionally on every turn that exceeds budget. Each call:

1. Renders ~N/2 frames as a transcript (token-cost: O(N/2)).
2. Invokes the compressor's model with that transcript (provider
   call — token + dollar cost).
3. Persists the resulting `ContextSummary` to the DB.

Step 3's persisted summary is never reused. The next turn that
sheds against the same older-half frame range pays steps 1–3
again, producing a fresh ContextSummary record (different `_id`)
covering an overlapping range.

For Sage's "small-model conversation + occasional large-history
imports" pattern, this becomes per-turn-per-conversation compute
debt:

- After importing a 9K-event Claude Code session, every gemma
  ConversationWork turn triggers Stage 3.
- Each Stage 3 invokes compress() against the older half of the
  view's frames — almost the same range every time, just shifted
  by the most-recent event or two.
- Each compress() pays the full re-summarization cost.

The summarization model's tokens accumulate across turns even
though the input is essentially the same content.

**Suggested fix — cache by frame-range key:**

Each `ContextSummary` could carry the (start, end) frame-id range
it covers. The curator's Stage 3, before calling compress(), looks
for a persisted summary whose range covers (or is close enough to)
the current older-half:

```scala
case class ContextSummary(
  text: String,
  conversationId: Id[Conversation],
  rangeStart: Id[Event],     // NEW — first frame in the summarized range
  rangeEnd:   Id[Event],     // NEW — last frame
  tokenEstimate: Int,
  ...
)

// In Stage 3:
val olderRange = (older.head.sourceEventId, older.last.sourceEventId)
findExistingSummary(conversationId, olderRange).flatMap {
  case Some(existing) => Task.pure(Some(existing))    // reuse
  case None           => compressor.compress(...)     // recompute
}
```

Lookup is keyed on `(conversationId, rangeEnd)` with a check that
`rangeStart` matches or is older than the older-half's start. Even
a strict-equality cache hits nearly every turn after a stable
import — the older-half frame ids don't shift much turn-to-turn.

For partial overlap (the older-half grew by one frame because of
the previous turn's reply), several reasonable policies:

- **Strict equality:** only reuse exact matches. Misses on every
  turn that adds a frame; degenerate.
- **Range-superset:** reuse a summary whose range is a superset of
  the requested older-half. Hits often.
- **Range-subset + delta-summarize:** reuse a summary covering most
  of the range, then issue a small compress() for just the new
  frames + the existing summary text. Best quality, modest extra
  cost.

Range-superset is the cheapest first cut. Delta-summarize is the
optimal long-term shape but adds compressor calls for a different
purpose.

**Belt-and-suspenders:** even without range-aware caching, an
identity cache keyed on the hash of the older-half's
sourceEventIds eliminates redundant compress() calls when the
older-half is bit-identical to a prior call. Cheap to implement,
catches the common "agent took one turn, shedding ran twice on
identical input" case (e.g. when an agent run iterates internally
on tool-result observations).

**What Sage does today:** wait. The cost manifests after large
imports where every gemma turn pays the compress() bill, but
correctness is fine — bugs #23 and #24 are the prerequisites for
the use case to work at all. Once those land, this becomes the
next visible perf concern.
