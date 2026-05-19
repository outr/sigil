# ❌ #243 — `dispatch_workers(confirmed=false)` returns raw items list instead of `ScopePreview` with worker count + `confirmCall` directive — agent can't tell preview from completed dispatch

**Where:**
- `tooling/src/main/scala/sigil/tooling/dispatch/DispatchWorkersTool.scala`
  — `executeTyped`'s `confirmed = false` branch. Per #230's spec
  this should return a `RefactorWithInstructionScope` /
  `DispatchWorkersScope` shape with estimated worker count,
  resolved model id, and a `confirmCall` directive. Currently
  appears to return the raw resolved items as if work happened.
- `tooling/src/main/scala/sigil/tooling/dispatch/DispatchWorkersOutput.scala`
  (or wherever the output type lives) — needs a `ScopePreview`
  variant alongside the dispatch result.

**What's wrong:**

#230's two-phase confirm was supposed to give the agent (and the
human reading the wire log) a visible decision point before
spending money on N parallel LLM worker calls. The agent calls
`dispatch_workers(confirmed=false)` first; the framework returns
a scope preview — "would dispatch N workers at model X, no LLM
calls have run yet, call again with confirmed=true to proceed."
The agent reads the preview, decides, and either re-calls with
`confirmed=true` or aborts.

In current behavior, the preview branch just returns the items
from the source (`FromCall(grepCallId)`) packaged as a Success.
Field evidence — Sage event log 2026-05-19 12:09:32:

```
ToolInvoke {
  toolName: "dispatch_workers",
  input: {
    "complexity": "Medium",
    "confirmed": false,
    "items": { "type": "FromCall", "callId": "RRWh...", "groupBy": "TopLevelOnly" },
    "pipeline": { "llm": { "prompt": "...", "outputSchema": {...} } }
  }
}

ToolResults {
  outcome: { "type": "Success" },
  summary: "{\"items\":[{filePath,matchCount,...},...]}",
  typed: { items: [/* FileMatches from the grep */] }
}
```

The result is **identical in shape to the grep result that fed
it**. There's no `totalFiles`, no `estimatedWorkerCallCount`,
no `resolvedModelId`, no `confirmCall` text, no scope-preview
discriminator. From the agent's perspective, `outcome: Success`
+ a list of items means "the work happened and here are the
results." It has no signal that it needs to make a follow-up
call with `confirmed: true` to actually dispatch the workers.

Additional impact: completion time was **96ms** — far too fast
to be actual LLM worker dispatch. That's the visible
discrepancy that surfaced the bug. With the right preview
output naming the expected worker count, the user would have
seen "Would dispatch 47 workers at moonshotai/kimi-k2.6, est.
cost $X. Call confirmed=true to proceed." — clear that no
workers ran.

The truncation warning at the end of `summary` (`result is
16871 bytes (threshold 8192). Truncated inline.`) compounds the
problem: even what the agent does see is incomplete.

**Suggested fix:**

Add a `ScopePreview` output variant on `DispatchWorkersOutput`,
have the `confirmed=false` branch return it. Per #230's spec:

```scala
// DispatchWorkersOutput.scala — add the variant
sealed trait DispatchWorkersOutput derives RW

object DispatchWorkersOutput {
  /** Scope-preview result for `confirmed=false`. No workers ran. */
  case class ScopePreview(
    sessionId:               String,
    totalItems:              Int,
    estimatedWorkerCallCount: Int,     // == totalItems for PerItem; == groups for ByKey
    resolvedModelId:         String,
    estimatedCostNote:       String,   // e.g. "47 calls at moonshotai/kimi-k2.6: ~$0.12 input + variable output"
    perItemSample:           List[Json], // first ~5 items so the agent can sanity-check
    confirmCall:             String     // human-readable directive for the agent
  ) extends DispatchWorkersOutput derives RW

  /** Actual dispatch result for `confirmed=true`. */
  case class DispatchResult(
    sessionId: String,
    perItem:   List[WorkerOutcome],
    // ...
  ) extends DispatchWorkersOutput derives RW
}
```

The `confirmCall` field should be explicit and self-contained:

```
"call dispatch_workers again with the same arguments and confirmed=true to dispatch 47 workers"
```

The agent reads that line, understands "preview gave me the
scope; I need to re-issue to actually run."

### Why a separate output variant matters

The orthogonal alternative — returning the same `DispatchResult`
shape but with `perItem` empty and a flag — invites the agent
to misinterpret. With a distinct ScopePreview variant:

- The agent's tool-result schema discriminates between the two
  cases at the type level
- Mistakes are visible in the wire log ("you got a ScopePreview
  back; did you mean to confirm?")
- Tome / consumer UIs can render the preview differently
  (a "ready to dispatch?" affordance with the cost call-out)

### Truncation handling

The 16871-byte result hitting the 8192 inline threshold is a
real concern — but should be fixed by ScopePreview being
SMALL by design. The preview body should be:

- Total counts (small ints)
- Resolved model id (small string)
- Cost note (one short sentence)
- A SAMPLE of items (first 5-10), not all 47

Full per-item details aren't needed for the preview — the
agent already knows the items (it constructed the source).
Sampling keeps the result well under any inline-truncation
threshold.

### Failing test

Under `core/src/test/scala/spec/DispatchWorkersScopePreviewSpec.scala`:

1. **`confirmed=false` returns a ScopePreview, not a DispatchResult:**
   - Build inputs with `FromList(items=[...])` and `confirmed=false`.
   - Call dispatch_workers.
   - Assert: result is `DispatchWorkersOutput.ScopePreview`, NOT
     `DispatchWorkersOutput.DispatchResult`.
   - Assert: NO worker LLM calls were dispatched.

2. **ScopePreview includes the expected fields:**
   - Assert: `totalItems == 47` (or whatever the test input has).
   - Assert: `estimatedWorkerCallCount == 47`.
   - Assert: `resolvedModelId.isNotEmpty`.
   - Assert: `confirmCall.contains("confirmed=true")`.

3. **ScopePreview body stays small (under inline-truncation threshold):**
   - Build inputs with 1000 items.
   - Capture serialized result size.
   - Assert: size < 8192 bytes (the inline truncation threshold).

4. **`confirmed=true` proceeds to actual dispatch:**
   - Same inputs but `confirmed=true`.
   - Assert: result is `DispatchWorkersOutput.DispatchResult`.
   - Assert: workers ran (stub-counted).

All four must FAIL on current SNAPSHOT; pass after the fix.

**Related:**
- **#230** — original dispatch_workers redesign spec. Defined the
  two-phase confirm pattern; this bug is the implementation gap.
- **#224** — same two-phase confirm pattern on the prior
  `refactor_with_instruction` tool. The pattern was the right
  shape; the new dispatch_workers tool just lost the preview
  output type in implementation.
- **#229** — service status surface. Independent — different bug.
- **Sage event log (2026-05-19 12:09:32)** — field evidence.
  `dispatch_workers(confirmed=false)` returned in 96ms with a
  Success outcome and the raw FileMatch list, indistinguishable
  from a normal grep result. Agent moved on without ever
  re-issuing with `confirmed=true`.
