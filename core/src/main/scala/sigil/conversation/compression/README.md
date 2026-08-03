# Compression, memory extraction & retrieval

Three pathways write durable facts into the memory store, and one pipeline reads them back. Apps typically wire at least one writer; the reader is on by default.

## Writing

### 1. Pinned memories — always injected

A `ContextMemory` with `pinned = true` is surfaced by `StandardMemoryRetriever` on every turn, unconditionally, in the provider's "Pinned directives" section. Apps seed these with `Sigil.persistMemory` (or `upsertMemoryByKey` for a versioned slot) when the fact must stay visible regardless of how the user phrases their question — "always reply in JSON", hard output constraints, standing rules.

Pinning is a property of the record, not of `MemorySource`: `source` records where the fact came from (`UserInput`, `Explicit`, `Compression`), while `pinned` decides whether it renders every turn. `pin_memory` / `unpin_memory` flip the flag at runtime; `Sigil.coreContextShareLimit` caps how much of the model window the pinned set may occupy.

A `modeAffinity` set narrows a pinned directive to specific `Mode`s, so a coding-only rule doesn't burn budget in conversation turns.

**When:** outside the conversation loop (app boot, or whenever the constraint changes) — or at runtime via `pin_memory`.
**Extracted by:** nothing — apps and agents write these directly.

### 2. Compression-time extraction

`MemoryContextCompressor` runs a two-pass LLM call when the curator sheds history:

1. **Extract** — consult the model with `ExtractMemoriesTool`; persist each returned fact as a `ContextMemory` with `source = MemorySource.Compression` and the shed slice's event ids as `sourceEventIds`.
2. **Summarize** — consult the model with `SummarizationTool`; persist the resulting `ContextSummary`.

The target space comes from `Sigil.compressionMemorySpace(conversationId)`; returning `None` disables extraction and collapses the compressor to summary-only. Chunks larger than the summarization model's window are chunked and extracted per chunk.

Before spending a consult on a chunk, the compressor runs the app's chosen `HighSignalFilter` — the same filter the per-turn extractor gates on, read off `Sigil.memoryExtractor.signalFilter`. An extractor with no filter (or `NoOpMemoryExtractor`) leaves the leg ungated.

**When:** the curator decides compression is needed (long conversations, token pressure).
**Extracted by:** `ExtractMemoriesTool` — keyed facts version, keyless ones append.

### 3. Per-turn extraction

`StandardMemoryExtractor` runs after every agent `Done` event on a background fiber. It:

1. Runs `filter.isHighSignal(turn)` to skip low-value turns cheaply (no LLM call on small-talk).
2. Consults the model with `ExtractMemoriesTool` — yields structured `(key, label, content, tags)` entries.
3. Persists each via `Sigil.persistMemoriesFor`, which routes keyed entries through `upsertMemoryByKey` so repeat facts version rather than duplicate.

Wired via `Sigil.memoryExtractor` (default `NoOpMemoryExtractor`).

**When:** after every agent turn, fire-and-forget.
**Status:** `defaultStatus`, which is **`MemoryStatus.Approved`** — extracted memories surface on the next turn without gating. Apps with a human-in-the-loop review UX set `defaultStatus = MemoryStatus.Pending` and drive `Sigil.listPendingMemories` / `approveMemory` / `rejectMemory`.

### The turn the extractors see

`ExtractionTurn` is what both extraction pathways judge and consult over:

```scala
case class ExtractionTurn(userMessage: String,
                          agentResponse: String,
                          sourceEventIds: List[Id[Event]] = Nil,
                          settledMutations: List[ToolName] = Nil)
```

`sourceEventIds` is event-grain provenance, stamped onto every extracted memory. `settledMutations` names the tools that settled successfully during the turn with a `Mutating` / `Destructive` effect profile — structured evidence that text alone can't express. Both pathways supply it: the per-turn leg from the turn's `ToolInvoke` rows, the compression leg from the shed slice's settled `ContextFrame.ToolCall` frames.

`MemoryExtractor.extract(userMessage, agentResponse)` remains for text-only implementations; `extractTurn` is the rich entry point and the one the framework calls, with a default that delegates to `extract`.

### High-signal filters

- `DefaultHighSignalFilter` — personal-assistant idioms (purchases, family, amounts, explicit "remember that").
- `AgenticSignalFilter` — coding / agentic corpora: any turn with settled mutations, decision and constraint language ("decided", "instead of", "must never", "convention"), error-class names, version pins, and explicit user corrections following an agent action.
- `HighSignalFilter.any(a, b, …)` — widen without replacing.

## Reading

`StandardMemoryRetriever` produces two buckets per turn and a `MemoryRetrievalResult` of ids (the provider hydrates records at render time, so an update is visible everywhere at once).

- **`criticalMemories`** — every pinned, recallable memory in the caller's accessible spaces whose `modeAffinity` admits the current mode. Not subject to the `limit`.
- **`memories`** — the top-K relevant non-pinned memories, produced by a declared six-stage pipeline:

| Stage | Does |
|---|---|
| `RecallStage` | Two legs — vector (`Sigil.searchMemories`, space scope pushed into the index's top-K cut) and lexical (BM25 over `ContextMemory.searchText`, with spaces / `pinned = false` / `Approved` compiled into the Lucene query). Query tokens are deduplicated and capped. |
| `GateStage` | The shared recall predicate: `isRecallable` (current version, `Approved`, unexpired), unpinned, mode affinity. |
| `FuseStage` | Confidence-weighted Reciprocal Rank Fusion across the legs, scaled by bounded recency and reinforcement terms. |
| `RerankStage` | Optional reorder (`LLMMemoryReranker` or a custom `MemoryReranker`). Off by default; a failure or a non-permutation result keeps the fused order. |
| `BudgetStage` | Pinned exclusion, then the count cap, then an optional rendered-token cap kept as a best-first prefix. |
| `RecordStage` | Marks `accessCount` / `lastAccessedAt` on the surfaced set, feeding the next turn's reinforcement term. |

Apps swap a stage by starting from `StandardMemoryRetriever.defaultStages(...)`, replacing the entry, and passing `pipeline = Some(...)`.

Retrieval results are cached per conversation (`MemoryRetrievalCache`) so an agent's iteration burst sees a stable memory set and pays for one retrieval per user-driven turn. The cache drops a conversation's entry when a non-agent message or a topic `Switch` settles, and drops every entry (via a global epoch bump) when a memory write changes what is recallable.

Access marking accumulates in memory and lands on the `MemoryAccessFlushTask` cadence (`Sigil.memoryAccessFlushInterval`, default 60s) plus once at shutdown — it is a ranking signal, not conversation state, and doesn't justify a store commit per turn. A crash loses at most one interval of counts.

## Consolidation

`MemoryConsolidationTask` is an opt-in maintenance sweep that keeps a keyless corpus from growing monotonically noisier. Per configured space it loads the keyless, unpinned, non-expiring, recallable memories (newest first), clusters near-duplicates through the vector index, and routes each cluster through a cheap-tier `ConsolidateMemoriesTool` consult for a merge / keep-separate verdict.

A cluster only forms among memories with **identical `modeAffinity`** — merging across scopes would either escalate a mode-scoped memory to universal or demote a universal one. A merge verdict is validated before it is applied (non-empty, not disproportionately longer than its inputs, and sharing a real fraction of some member's content words); a merge that fails degrades to keep-separate with a warning, because applying it archives records the user actually stated.

Merges go through the standard versioning fields: a new record supersedes the cluster, each member gets `validUntil` + `supersededBy` and its vector point deleted. Nothing is hard-deleted; `memoryHistory` still sees every version.

Not in `Sigil.maintenanceTasks` by default — it spends LLM calls and rewrites memory rows, so activation is an app decision.

## Provenance

Every extracted memory carries `sourceEventIds` — the durable `Event` ids of the exchange it came from. A keyed `Refreshed` write unions the prior record's ids with the new extraction's (bounded to the most recent `MemoryOps.MaxSourceEventIds`); a `Versioned` write starts the new record with only the new extraction's ids, and the superseded version keeps its own. `memory_history(key, space)` walks the version chain.

## When to wire which

- **Pinned only** — apps with a small set of hard constraints and no interest in long-term memory.
- **Compression only** — memory capture without the per-turn latency; memories land lazily when compression fires.
- **Per-turn only** — apps whose UX depends on fresh capture. One LLM call per high-signal turn.
- **Per-turn + compression** — the recommended combination. Per-turn captures keyed facts as they happen; compression catches what slipped through when the budget forces summarization.

All coexist without conflict — the retriever surfaces memories by space and the recall gate regardless of which pathway produced them.

## Key invariants

- The recall gate (`ContextMemory.isRecallable`) is applied by every read surface, including the provider's final hydration — a memory revoked mid-burst stops rendering on the next wire call, not on the next user message.
- Every path that takes a record out of the recallable set deletes its vector point. A point left behind is the one way an archived record can re-enter a prompt, since the semantic leg's candidate pool is the one the store-side filter can't pre-narrow.
- `key` is `Option[String]`. Keyed records version through `upsertMemoryByKey`; keyless records append. `persistMemories` routes each record by that distinction and shares one batched embedding request.
- `MemoryExtractor` failures are logged but never propagate to the agent's response stream, and a retrieval failure degrades the turn to "no memories surfaced" rather than failing it.
