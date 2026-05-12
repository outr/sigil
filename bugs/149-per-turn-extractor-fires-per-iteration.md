# ❌ #149 — Per-turn `memoryExtractor` fires once per agent-loop iteration; should fire once per user turn

**Where:**
- `core/src/main/scala/sigil/orchestrator/Orchestrator.scala:712` — `fireMemoryExtractor(sigil, request, state).startUnit()`
- `core/src/main/scala/sigil/orchestrator/Orchestrator.scala:960-984` — `fireMemoryExtractor` body

**What's wrong:**

The orchestrator's agent loop fires `sigil.memoryExtractor.extract(...)` after every iteration's `Done` event. On turns where the agent calls multiple tools and iterates several times (typical for any non-trivial coding task), this means N extraction calls per user turn — even though the relevant content (user message + agent's eventual response) is mostly the same across iterations.

Observed in a Sage wire log over a single user turn:

```
L60   20:57:55  extract_memories  prompt=922 tok, completion=74 tok
L65   20:57:58  extract_memories  prompt=922 tok, completion=74 tok   ← same payload
L104  20:58:19  extract_memories  prompt=972 tok, completion=134 tok  ← growing transcript
L118  20:58:21  extract_memories  prompt=972 tok, completion=134 tok  ← duplicate
L123  20:58:31  extract_memories  prompt=972 tok, completion=134 tok  ← triplicate
```

Each fire is a real LLM round-trip + a real write through `StandardMemoryExtractor.extract` → `upsertMemoryByKey` / `persistMemoryFor`. The DB ends up with multiple slightly-different records describing the same facts; the memory store accumulates duplicates that the retriever later has to RRF-rank and the agent has to reconcile.

The orchestrator's intent (per the comment around line 956: *"...best-effort latency-hidden work."*) is that extraction is one fire-and-forget pass capturing what was learned during the turn. The current implementation conflates "iteration" with "turn" — they're not the same thing once the agent loop exists.

**Suggested fix:**

Move the `fireMemoryExtractor` call out of the per-iteration completion path and into the user-turn-boundary path. The orchestrator already has a clear notion of turn completion — the agent loop terminates when `respond` is called, when `maxAgentIterations` is hit, or when the user cancels. Fire extractor exactly once at that point with the final accumulated state.

Concrete shape:

```scala
// Remove the per-iteration fire (line 712).
// Add a single fire at the agent-loop termination site, e.g. after the
// respond branch settles or after the maxAgentIterations guard fires:

private def settleTurn(sigil: Sigil, request: ConversationRequest, state: State): Task[Unit] =
  fireMemoryExtractor(sigil, request, state).startUnit()
```

The `state.turnBuffer` and `request.turnInput.frames` already contain everything extraction needs at turn end. Per-turn semantics restored; one LLM call per user turn instead of N.

**Severity:** High. Triples-to-N-tuples the cost of per-turn extraction on any multi-tool turn. Pollutes the memory store with overlapping records that downstream retrieval has to disambiguate. The "best-effort latency-hidden" comment is accurate as written, but the multiplied cost is no longer latency-hidden when N is large (each extraction is 10-20 s on local llama; five of them per turn is a minute of unnecessary background work).

Prevention fix, not recovery fix — extraction simply runs at the correct grain.
