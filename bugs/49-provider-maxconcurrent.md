# ❌ #49 — `Provider` lacks a `maxConcurrent` capacity declaration; backends queue opaquely

**Where:**
- `core/src/main/scala/sigil/provider/Provider.scala` — no concurrency-
  capacity field on the trait. Calls go out through spice `HttpClient`
  with no per-provider semaphore or queue gate.
- `core/src/main/scala/sigil/provider/llamacpp/LlamaCppProvider.scala`
  — already reads `total_slots` from `/props` (per the bug #42 fix
  that pulls runtime context from the same endpoint), but the value
  isn't propagated to a capacity model.
- Effect: when Sigil makes more concurrent calls than a backend can
  serve, requests queue *inside* the backend's HTTP server. With
  `total_slots = 1` (Sage's default), a slow `/apply-template`
  blocks every subsequent call — pre-flight, follow-up
  `/v1/chat/completions`, `/tokenize`, etc. — until it finishes.
  The framework can't see the queue; can't surface "waiting for
  capacity"; can't prioritize live agent calls over advisory pre-
  flight; can't cancel queued items because they're already in the
  backend's plumbing.

**What's wrong:** Sigil treats every Provider as having infinite
concurrent capacity. That's correct for OpenAI / Anthropic (their
binding constraints are RPM/TPM rate limits, not slot count) but
wrong for local backends where slot count is the literal dispatch
constraint. The result is a pile of pathologies any local-llama.cpp
consumer eventually hits:

- **Head-of-line blocking by slow advisory calls.** A 46-second
  `/apply-template` on a model with quadratic chat-template
  rendering (e.g., gemma 4) blocks the agent's actual
  `/v1/chat/completions` call from even being dispatched until the
  pre-flight finishes.
- **Retry-stall amplification.** spice's `RetryManager` retries
  failed calls after 1s. If a call fails because it's stuck behind
  capacity (not because the backend is broken), retrying just
  re-queues behind the same blocker.
- **Opaque "Active" UI.** The user sees a Stop button with no
  signal about what's happening — backend is busy, but the
  framework can't say what it's busy with.
- **No prioritization.** All calls compete for slots equally.
  Live agent turns wait the same as advisory pre-flight estimates.

**Suggested fix — model concurrency client-side.**

```scala
trait Provider {
  ...
  /** Maximum concurrent calls this provider will dispatch. The
    * runtime acquires from a per-provider semaphore before each
    * `apply` / `estimateRequest` / advisory call; queued calls
    * are visible to the framework (cancellable, prioritizable,
    * surfaceable to the UI) instead of opaquely buffered inside
    * the backend's HTTP server.
    *
    * `Int.MaxValue` (the default) means "no client-side limit",
    * suitable for cloud providers whose binding constraint is
    * rate-limit (RPM/TPM), not slot count. Local backends with
    * a finite-slot model should override. */
  def maxConcurrent: Int = Int.MaxValue
}

class LlamaCppProvider(url: URL, models: List[Model], sigilRef: Sigil) extends Provider {
  ...
  /** From `/props.total_slots` at startup (already read for the
    * bug #42 contextLength derivation). Defaults to 1 when /props
    * is unreachable — single-slot is the safe assumption for an
    * unknown llama.cpp deployment. */
  override val maxConcurrent: Int = totalSlotsFromProps.getOrElse(1)
}
```

Wire a per-provider semaphore (or capacity-aware queue) into
`Provider.apply` / `Provider.estimateRequest` / any advisory call
shape so all dispatches gate through it. Three coupled design
choices to nail:

**1. Priority.** Live agent `/v1/chat/completions` outranks
advisory pre-flight `/apply-template` and `/tokenize`. Otherwise
pre-flight starves the actual turn — it's pathological for
"estimate the prompt" to delay sending the prompt. Two-class
priority (live + advisory) is enough; finer granularity can come
later.

**2. Cancellation propagation.** Queued items must die when the
agent's `runAgentLoop` is cancelled (Stop button, conversation
abandonment). Without this you re-create the unbounded-queue UX
problem — slow calls pin the user even after they've asked to
stop.

**3. Observability.** Surface "queued vs in-flight" so consumers
can render meaningful states. Pairs naturally with bug #50
(workflow lifecycle for framework operations) — a queued advisory
call is a workflow in the `Pending` state.

**What this enables:**
- Eliminates retry-stall pathology at the source (calls don't
  fail because of capacity; they wait their turn).
- Lets pre-flight be fast in the common case and *visible* (per
  bug #50) when it's slow.
- Composes with the per-workflow cancellation tool (bug #51) —
  cancelling a workflow can drop its queued provider calls
  without affecting other in-flight work.

**Why this is the framework's job:**
Every Sigil consumer running local llama.cpp at meaningful scale
hits the same capacity-vs-blocking pattern. Per-app semaphore
re-implementation is wasted work; the right place is at the
Provider abstraction where the capacity declaration lives
naturally.
