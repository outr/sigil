# ❌ #289 — Every `ImageToolOutput` image accumulates in conversation history; no dedup/expiry on superseded preview images

**Where:**
- `core/src/main/scala/sigil/conversation/compression/IntraTurnCompactor.scala` (and `StandardIntraTurnCompactor`) — the eligibility rules for what gets folded mid-turn don't currently special-case `ImageToolOutput` results.
- The conversation-replay path that builds `messages[]` for each provider request — same place that re-ships agent tool_use content (#288).

**What's wrong:** `ImageToolOutput` (#280) is a substantial win for agent
self-verification — the agent can actually see the pixels it produced.
But each image becomes a permanent ~50-100 KB content block in the
conversation history. Repeated calls to a preview tool stack up:

```
[entry 51] preview_theme → ImageToolOutput (~80 KB image of /pages/X v1)
[entry 61] preview_theme → ImageToolOutput (~80 KB image of /pages/X v2)
[entry 83] preview_theme → ImageToolOutput (~80 KB image of /pages/Y v1)
[entry 107] preview_theme → ImageToolOutput (~80 KB image of /pages/Y v2)
[entry 109] preview_theme → ImageToolOutput (~80 KB image of /pages/Y v2)
   ← exact duplicate of 107: same input, same state, same image
```

By entry 110, the agent's prompt carries five preview images even though
only the most recent one carries useful information for the current
reasoning. The earlier ones are visually superseded — the agent doesn't
need to look at v1 of page X when v2 is right there.

Concrete impact (Shopkeeper, 2026-05-26):

- 5 preview images × ~80 KB each = ~400 KB of accumulated image bytes in
  every request from entry ~107 onward
- Several duplicate preview_theme calls compound the waste (skill update
  shipping today addresses the agent-side discipline; framework-side
  expiry would catch what the agent gets wrong anyway)
- Combined with #288's permanent-tool_use bloat, image accumulation pushes
  per-request bodies to 2 MB+ — past the rate-limit-safe threshold even
  with #283/#284

**Suggested fix shape:**

1. **Image-output supersession in `StandardIntraTurnCompactor`.**
   Extend the existing folding rules with: "for any ImageToolOutput
   produced by tool T with input I, if there's a later
   ImageToolOutput from the same (T, I) tuple, the earlier one
   collapses to a textual stub:
   ```
   [preview_theme image of /pages/X — superseded by later preview at iter N]
   ```
   The latest image stays inline so the agent can still see current
   state."

2. **Cross-tuple staleness rule.** Even when (tool, input) differs,
   images older than the most recent N (default 1 — only keep the
   latest) collapse to text references. Apps that need multiple
   in-context images (a side-by-side compare tool, an OCR multi-page
   reader) override `keepRecentImages: Int = 1` to widen.

3. **Reference format the agent can re-fetch.** The stub is a
   `lookup`-able id pointing at the original `ImageToolOutput.url`:
   ```
   [image suppressed for context budget — ref: img-abc123,
    192 KB, "Preview of /pages/X at 1280×800 (iter 23)"]
   ```
   The agent can fetch the actual pixels back via a `view_image_ref`
   tool or by re-invoking the producing tool.

4. **Apply BEFORE shipping to the provider, not eagerly at
   produce-time.** The conversation log keeps every image (the human
   user wants to scroll back through history and see them). Only
   the *rendered prompt* sent to the provider gets the dedupe pass.

**Why this matters beyond Shopkeeper:**

Any agent producing visual output as tool results hits this:

- Coding agents with screenshot-based UI verification
- Data agents producing rendering of charts / diagrams
- Web research agents capturing browser screenshots
- Image generators iterating on a design

The "every image is permanent in context" pattern bounds these workflows
to ~10-15 image-producing iterations before the input budget breaks.
With dedupe, the same workflows fit comfortably in 50+ iterations.

**Cross-refs:**
- #280 — introduced `ImageToolOutput`. This bug is the missing
  lifecycle policy on top of it.
- #285 — intra-turn compaction. #289 specifies image-specific
  eligibility rules that the default compactor doesn't cover today.
- #283 / #284 — rate-limit guard. #289 is one of the biggest
  per-iteration size reductions available for visual-output agents.

**Diagnostic data:**
- Wire log:
  `/home/mhicks/projects/clients/outr/shopkeeper/backend/logs/shopkeeper-wire.jsonl`
- Entries 52, 62, 84, 88, 96 — successive request bodies showing
  cumulative image accumulation. Entries 107 and 109 are an exact
  back-to-back duplicate `preview_theme` calls (separate sub-issue,
  agent-side; skill update addresses).
