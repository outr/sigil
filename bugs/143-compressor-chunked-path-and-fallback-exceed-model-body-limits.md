# ❌ #143 — `MemoryContextCompressor` chunked path produces chunks exceeding provider body limits; fallback selects a model that can't fit the input either

**Where:**
- `core/src/main/scala/sigil/conversation/compression/MemoryContextCompressor.scala`
  - `compress` (around line 54) — orchestrates extraction + summarization
  - `extractAndPersistChunked` (around line 91 + line 172) — chunking fallback
  - `summarize` (around line 96 + line 203) — summarization call
- `core/src/main/scala/sigil/Sigil.scala:1215+` — `routedModelFor`'s `fits` predicate + fallback semantics

**What's wrong:**

On a bulk-imported conversation with ~48,000 frames, the compressor produces a single transcript that's ~18 MB even AFTER `StandardBlockExtractor` has replaced large frame bodies with `Information[id]` placeholders. The placeholder lines + per-frame headers + role markers, multiplied across 48K frames, total 10-15 MB on their own. The compressor never sees the original content but its renderer still concatenates 48,000 placeholder-bearing lines into one transcript string.

Two failure paths follow:

1. **Routing falls through to a frontier model.** `routedModelFor(SummarizationWork, chain, fallback = callerModelId, estimatedInputTokens = Some(transcriptTokens), reservedOutputTokens = 1024)` filters candidates by the `fits` predicate (does the model's context hold the input?). At 18 MB ≈ ~5M tokens, **no registered candidate fits**, so the routing falls through to the supplied fallback — which is `callerModelId`, the model that handled the user's most recent turn. In a cost-routed setup that's frequently a frontier model (gpt-5.5 / claude-opus-4-7) because the classifier escalated for the user's question. The compressor then hits the provider with 18 MB and gets HTTP 400:
   ```
   "Invalid 'input[0].content[0].text': string too long. Expected a string with maximum length 10485760, but got a string with length 18300230 instead."
   ```
   Same on the summarization pass two milliseconds later (18,300,051 chars).

2. **Even when routing picks a "fits" model, the request body exceeds the provider's wire limit.** OpenAI's per-text-input ceiling is 10,485,760 chars regardless of the model's claimed context. llama.cpp's HTTP server rejects similarly large bodies — same conversation produced two follow-up errors:
   ```
   Request to http://localhost:8081/tokenize permanently failed
   Request to http://localhost:8081/apply-template permanently failed
   ```
   The compressor's `extractAndPersistChunked` path exists for this scenario but the chunks it emits here are *still* over the wire limit — either the chunking math is computing chunk size in tokens against the model's context window and ignoring the per-text-input byte cap, or chunks aren't actually being split fine-grained enough on 48K-frame inputs.

**Suggested fix:**

Three layers, in order of impact:

1. **Add an explicit per-text-input byte ceiling to the compressor's chunk-size calc.** A new knob on `MemoryContextCompressor`:
   ```scala
   maxChunkBytes: Long = 8_000_000L  // safe under OpenAI's 10MB + llama.cpp's smaller limit
   ```
   `extractAndPersistChunked` (and the summarize chunker) must produce chunks where `chunk.getBytes.length <= maxChunkBytes`, not just `tokens(chunk) <= available`. Token-window math is necessary but not sufficient — every provider has a wire-protocol body cap below their claimed context.

2. **Refuse, don't fall through.** When `routedModelFor`'s `fits` filter returns no candidates AND a body cap is in play, the right behavior is to refuse compression with a structured error the caller can act on — not return the fallback to charge ahead with an input that can't possibly succeed. The current "fallback to `callerModelId` regardless of whether it fits" path turns a routing failure into a guaranteed HTTP 400 + dropped compression result. A `Task.raiseError(CompressionTooLarge(estimatedTokens, maxModelContext))` would let `MemoryContextCompressor.compress` swap to summary-only-on-most-recent-N-frames mode, which is still useful and never blows the body limit.

3. **Cap conversation history at the curator boundary.** Even with perfect chunking, asking the framework to summarize 48K frames in one turn isn't useful — by the time it's done the user has waited 10 minutes and the resulting summary is too lossy to inform the next response. A new curator knob:
   ```scala
   maxFramesPerTurn: Int = 5000  // hard cap; older frames stay in the DB but skip the curate pass
   ```
   When exceeded, the curator hard-truncates to the most-recent N frames, skips compression entirely, and emits a `ConversationTooLargeForCurate` notice the UI can surface ("Showing the most recent 5000 of 48000 messages — older history is still in the DB but isn't included in this turn"). The current always-curate-everything semantics breaks at bulk-import scale.

**Severity:** High. The compressor swallows the HTTP 400 (it `handleError` logs + drops the result), so the agent loop continues — but every subsequent turn pays the same cost AND silently loses the compression's intended value. From the user's perspective, the agent works but seems to ignore conversation history; from the operator's perspective, the wire log fills with HTTP 400s, and the frontier-tier provider gets billed for the failed calls.
