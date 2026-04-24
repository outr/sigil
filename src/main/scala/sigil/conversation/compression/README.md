# Compression & Memory Extraction

Sigil has three distinct pathways for getting durable facts into the memory store. They complement each other — apps typically wire at least one, often all three.

## 1. Critical memories — always injected

Memories persisted with `source = MemorySource.Critical` are surfaced by `StandardMemoryRetriever` on every turn, unconditionally. Apps seed these via `Sigil.persistMemory` when the fact must stay visible (e.g. "always reply in JSON", hard constraints on output format).

**When:** before/outside the conversation loop (at app boot, or whenever the constraint changes).
**Extracted by:** nothing — apps write these directly.
**Storage:** `ContextMemory` with `key = ""`, `status = Approved`.

## 2. Compression-time extraction

`MemoryContextCompressor` runs a two-pass LLM call during conversation compression:

1. **Extract** — consult the model with `ExtractMemoriesTool`, collect flat `facts: List[String]`, persist each as a `ContextMemory` with `source = MemorySource.Compression`.
2. **Summarize** — consult the model with `SummarizationTool`, persist the resulting `ContextSummary`.

Triggered by the curator when the rolling context exceeds its budget. Target space comes from `Sigil.compressionMemorySpace(conversationId)`; apps return `None` to disable extraction and collapse to summary-only.

**When:** curator decides compression is needed (long conversations, token pressure).
**Extracted by:** `ExtractMemoriesTool` (flat facts, no keys, no versioning).
**Storage:** `ContextMemory` with `key = ""`, `status = Approved`.

## 3. Per-turn extraction

`StandardMemoryExtractor` runs after every agent `Done` event on a background fiber. It:

1. Runs `HighSignalFilter.isHighSignal(userMessage)` to skip low-value turns cheaply (no LLM call on small-talk).
2. Consults the model with `ExtractMemoriesWithKeysTool` — yields structured `(key, label, content, tags)` entries.
3. Persists each via `Sigil.upsertMemoryByKey` so the framework automatically versions repeat facts across turns (same `key`, different `content` → supersedes the prior).

Wired via `Sigil.memoryExtractor` (default `NoOpMemoryExtractor`); apps that want this override the hook with `StandardMemoryExtractor` (or a custom implementation).

**When:** after every agent turn, fire-and-forget.
**Extracted by:** `ExtractMemoriesWithKeysTool` (keyed, supports versioning).
**Storage:** `ContextMemory` with a semantic `key`, `status = Pending` by default (apps with an approval UX transition to `Approved` via `Sigil.approveMemory`).

## When to wire which

- **Critical only** — for apps with a small set of hard constraints and no interest in long-term memory.
- **Compression only** — for apps that want memory capture but don't want the latency cost of per-turn extraction. Memories land lazily when conversation compression fires.
- **Per-turn only** — for apps whose UX depends on fresh memory capture (e.g. personal assistants). Adds one LLM call per high-signal turn.
- **Per-turn + compression** — the recommended combination for personal-assistant apps. Per-turn captures keyed facts as they happen; compression fills in anything that slipped through when the context budget forces summarization.

All three coexist without conflict — the retriever surfaces memories by space and status regardless of which pathway produced them.

## Key invariants

- Per-turn extraction uses `upsertMemoryByKey` → versioned. Compression extraction uses `persistMemory` → every fact is a distinct row.
- Auto-extracted (compression + per-turn) facts default to `MemoryStatus.Pending`. Apps with an approval inbox (`Sigil.listPendingMemories`) transition them. Apps without approval set `defaultStatus = Approved` on the extractor.
- `MemoryExtractor` failures are logged but never propagate to the agent's response stream.
