# ❌ #417 — a llama.cpp provider bound to a single-slot server wedges after the first turn: streams queue on the client-side slot gate while the server sits idle

**Severity:** High for any consumer pointing `LlamaCppProvider` at a server started without
`--parallel` (i.e. `total_slots: 1`, the llama.cpp default). The first agent turn succeeds and
every subsequent turn times out, with no error from the server and nothing in the wire log —
the provider is waiting on its own admission gate.

**Where:**
- `core/src/main/scala/sigil/provider/StreamSlotGate.scala` — `acquire` / `release`; permits.
- `core/src/main/scala/sigil/provider/llamacpp/LlamaCppProvider.scala:65` — `maxConcurrent`
  is read from the server's `/props` `total_slots`, falling back to 1. Against a default
  llama-server that is **1 permit for the provider's whole lifetime**.
- `LlamaCppProvider:137-146` — `StreamStarvationRelief` (`holdBatch` / `releaseBatchHold`).

**What's wrong:** with `permits = 1`, a permit that is acquired and not released strands every
later stream permanently. Observed in `benchmark/` driving `LongMemEvalQABench` against a
local `Qwen3.8-27B` server (`total_slots: 1`, `n_ctx: 60160`):

- question 0 completes normally (ingest → turn → judge);
- every question after it fails with `AgentBenchHarness: agent turn did not settle ... within
  600s — events seen so far: 2` (the agent claimed the turn and never emitted);
- the provider logs `Provider(llamacpp) stream slots busy (max=1) — queueing` and
  `LlamaCpp starvation relief engaged`.

**The server is not the bottleneck.** While the bench was stalled mid-question, a direct
`curl` to the same endpoint returned in **0.69s**:

```
$ time curl -s http://localhost:8081/v1/chat/completions -d '{...,"max_tokens":20}'
reply: READY
0.693 total
```

So the slot is held inside Sigil's accounting, not by llama.cpp. Starvation relief is not the
culprit either — the run logged `engaged: 1` / `cleared: 1`, balanced, so `batchHolds`
returns to zero. That leaves a permit acquired without a matching `release()`.

**Repro:**
1. `llama-server` with default parallelism (`total_slots: 1`).
2. Point a `Sigil` at it via `LlamaCppProvider` and run two agent turns in sequence, each
   followed by a `ConsultTool.invoke` on the same provider (the benchmark's turn-then-judge
   shape; probably any post-turn consult reproduces it).
3. Turn 1 completes. Turn 2 never settles; the provider logs `stream slots busy (max=1)`.

A 4-slot server (`llama.voidcraft.ai`, `total_slots: 4`) ran the same sequence past question
4 — i.e. past the point where a one-permit-per-question leak would have exhausted four
permits — with no stall. So this is **not a cumulative leak**: it is contention that
deadlocks only when there is zero headroom. Two streams that overlap for a moment are fine
at 4 permits and fatal at 1, which is why it went unnoticed — every existing spec and
benchmark points at the 4-slot public endpoint.

The overlap is between the turn's stream and a second call issued around the same time (the
post-turn consult, or the agent loop's trailing iteration). At `permits = 1` the second
waits for the first, and something in that ordering never completes.

**Where to look:** a hold-and-wait between two streams on the same provider, and any path
that acquires a permit but can exit without releasing it —
- a stream abandoned before terminal (`Stop`, orchestrator cancel, harness timeout);
- an error thrown between acquire and the release hook;
- a stream whose consumer stops draining (`.toList` on a cancelled task);
- the interaction between the agent loop's trailing iteration and a following consult on the
  same provider instance.

`StreamSlotGate.acquire` documents "Must be called exactly once per successful acquire" —
the defect is a path that doesn't honor it.

**Suggested fix:**
1. Tie release to stream termination structurally rather than by convention — acquire returns
   a handle whose release runs from a single `ensuring`/finalizer on every terminal path
   (complete, error, cancel), so no call site can forget it.
2. Add `availablePermits` to the provider's diagnostics and log at DEBUG on acquire/release
   with a stream id, so a leak is visible as a non-returning permit rather than as a mystery
   hang 600 seconds later.
3. Consider a defensive reaper: a permit held with no active stream past a threshold is
   reclaimed with a WARN. A leak should be loud, not fatal.
4. Regression spec: a fake provider with `maxConcurrent = 1` running turn → consult → turn,
   asserting the second turn settles. Today that hangs.

**Impact if unfixed:** any deployment against a single-slot llama.cpp server is
single-use — the first conversation turn works and the process is then wedged, with no
server-side error to point at. It also blocks benchmarking on a local single-slot model,
which is the configuration the "small runtime model" work depends on.
