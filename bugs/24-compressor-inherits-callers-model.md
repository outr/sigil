# ❌ #24 — Compressor inherits the calling agent's modelId; self-overflows when input ≫ that model's context

**Where:**
- `core/src/main/scala/sigil/conversation/compression/SummaryOnlyCompressor.scala:50-56`
  — `compress()` calls `ConsultTool.invoke(... modelId = modelId, ...)`.
  The `modelId` argument flows in unchanged from
  `StandardContextCurator.compressor.compress(sigil, modelId, ...)`,
  which itself is the agent-turn's modelId.

**What's wrong:** `compress()` asks the calling agent's model to
summarize a frame range. When the calling agent runs on a
small-context model and the older-half frame range is bigger than
that model's prompt budget, the summarization itself doesn't fit:

| Calling agent | view.frames size | Older-half input to compress() | compress() outcome |
| --- | --- | --- | --- |
| gemma (8K context) | 60 frames / 6K tokens | ~3K tokens | fits, summary returned |
| gemma (8K context) | 9,000 frames / 800K tokens | ~400K tokens | doesn't fit, gemma rejects, `handleError` swallows it, returns `None` |

When the compressor returns `None`, `StandardContextCurator`'s
Stage 3 falls through to the over-budget Stage 2 result. Provider
call then hits the actual provider rejection
(`exceed_context_size_error` for llama.cpp, equivalent for others).

This pairs with bug #23 (curator's Stage 3 doesn't iterate). Even if
#23 ships and Stage 3 recurses, **every iteration's compress() call
still uses the small-context model and self-overflows**, so deeper
shedding doesn't help the gemma case. #23 + #24 need to land
together for the small-context-conversation + big-history use case
to actually work.

**Concrete repro from Sage's giant-import scenario:**

1. `LoadClaudeStateTool` imports 9,117 events via `publishHistorical`.
2. Agent's next turn routes to gemma (`ConversationWork → cheapFirst`).
3. Curator Stage 3 splits 9,117 frames at half (post-#23 fix:
   recursively halves until under budget).
4. First compress() call: input ~400K tokens of older frames.
5. gemma is the modelId → compress() invokes gemma with ~400K-token
   prompt → gemma's 8K runtime cap → llama.cpp returns
   `exceed_context_size_error`.
6. SummaryOnlyCompressor's `handleError` arm swallows, returns
   `None`. Curator's Stage 3 returns Stage 2's over-budget result.
7. Outer agent-turn provider call rejects with the same shape as
   the original turn.

Even with iterative shedding (#23), this loop never converges —
each iteration's compress() bombs on its own input mass, never
producing the summary that would let the next iteration shrink.

**Suggested fix — compressor decides its own model:**

The compressor should route summarization-work through a
mechanism that picks a model with sufficient context for the
input, independent of the calling agent's model. Two reasonable
shapes:

**(a) `SummarizationWork` strategy lookup.** Sigil already has
`WorkType` for per-shape provider routing (post-bug-#17). The
compressor should resolve `SummarizationWork` against the active
`ProviderStrategy` rather than hard-coding the caller's modelId:

```scala
override def compress(sigil: Sigil, modelId: Id[Model], chain: List[ParticipantId],
                      frames: Vector[ContextFrame], conversationId: Id[Conversation]): Task[Option[ContextSummary]] =
  // pick a model big enough for the input — fall back to caller's
  // modelId if the strategy can't pick (no SummarizationWork
  // candidates, or all candidates also can't fit the input).
  resolveSummarizationModel(sigil, frames, callerFallback = modelId).flatMap { picked =>
    ConsultTool.invoke(... modelId = picked, ...)
  }
```

`resolveSummarizationModel` walks the SummarizationWork chain,
estimates the input token count, and picks the first candidate
whose `Model.contextLength` accommodates the input + a budget for
the summary output. If none qualifies (all SummarizationWork
candidates are smaller than the input), see (b).

**(b) Chunk-and-merge for inputs bigger than every available
model.** When even the largest available SummarizationWork
candidate can't fit the input, the compressor splits the input
into chunks each sized for the model, summarizes each chunk
independently, then merges the per-chunk summaries into one. This
adds depth but ensures the compressor never silently fails.

```scala
def compressLarge(sigil, model, frames): Task[Option[ContextSummary]] = {
  val budget = model.contextLength - summaryOutputBudget - promptOverhead
  val chunks = frames.grouped(framesPerBudget(budget))
  for {
    perChunkSummaries <- Task.sequence(chunks.map(summarizeChunk(model, _)))
    merged            <- mergeSummaries(model, perChunkSummaries.flatten)
  } yield merged
}
```

(a) is the lighter fix and resolves the user's case (frontier
model summarizes the input in one shot). (b) is the
belt-and-suspenders for cases where every available model is too
small. Both are useful; (a) ships first.

**App-side workaround Sage could apply today:** Sage's
`routingStrategy` could put a frontier model first in
`SummarizationWork`'s chain — but that doesn't help because the
compressor doesn't actually consult the strategy. The fix has to
land at the compressor's site.

**What Sage does today:** waits. Bug #23's deep-shedding work is
necessary but not sufficient for the giant-import use case; #24
unblocks it.
