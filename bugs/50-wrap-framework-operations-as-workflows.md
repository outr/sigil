# ❌ #50 — Long-running framework operations (pre-flight, compress, frame load, etc.) run invisibly; clients can't surface them

**Where:**
- `core/src/main/scala/sigil/provider/Provider.scala` (`preFlightGate`,
  `estimateRequest`) — pre-flight work runs synchronously inside
  `Provider.apply`, no lifecycle Notices.
- `core/src/main/scala/sigil/conversation/compression/SummaryOnlyCompressor.scala`
  — `compress` makes a real LLM call (often seconds), no progress
  signal.
- `core/src/main/scala/sigil/Sigil.scala` (`framesFor`, etc.) — large
  Lucene loads happen inline; clients see nothing.
- The framework already ships workflow lifecycle Notices —
  `WorkflowRunStarted` / `WorkflowStepCompleted` /
  `WorkflowRunCompleted` / `WorkflowRunFailed` /
  `WorkflowApprovalRequested` — but only application-level workflows
  emit them. Framework-internal long-running work is silent.

**What's wrong:** A turn that takes 60 seconds because `/apply-template`
is rendering 9000 messages on a quadratic chat template, or because
`compress()` is summarizing a giant older-tail, looks identical to
the user as a turn that's actually hung. The framework knows what
it's doing; it just can't tell anyone. Symptoms are downstream:

- Sage's UI shows "Active" with a Stop button for the whole 60
  seconds, no signal about why or whether progress is being made.
- Users can't make informed decisions ("wait it out" vs "cancel
  and try a smaller request") because they can't see what's
  happening.
- Operators / observability tooling have no hook into framework-
  internal latency — only the outer agent-turn duration shows up.
- Cancellation has nothing to grab onto — without a workflow
  identity, "cancel this specific operation" is impossible.

**Suggested fix — wrap framework long-running operations in workflow
lifecycle.**

The workflow Notice surface already exists; the work is making
framework-internal operations participants in that surface.
Concretely:

**Pre-flight as a workflow:**
```
WorkflowRunStarted(workflowId, type = "preflight", originAgent = ...)
  WorkflowStepCompleted(workflowId, step = "apply-template", durationMs = ...)
  WorkflowStepCompleted(workflowId, step = "tokenize", durationMs = ...)
  WorkflowStepCompleted(workflowId, step = "estimate-roster", durationMs = ...)
WorkflowRunCompleted(workflowId, outcome = Success, totalMs = ...)
```

**Compress as a workflow:**
```
WorkflowRunStarted(workflowId, type = "context-compress", originAgent = ...)
  WorkflowStepCompleted(workflowId, step = "render-transcript")
  WorkflowStepCompleted(workflowId, step = "consult-summarizer-model")
  WorkflowStepCompleted(workflowId, step = "persist-summary")
WorkflowRunCompleted(workflowId, outcome = Success)
```

**Frame-stream load (post-bug-#26 architecture):**
```
WorkflowRunStarted(workflowId, type = "frame-load", originAgent = ...)
  WorkflowStepCompleted(workflowId, step = "lucene-query", count = 9117)
  WorkflowStepCompleted(workflowId, step = "stage-3-shed")
WorkflowRunCompleted(workflowId, outcome = Success)
```

Same shape; same client surface. Sage can render any of these in
its activity bar without knowing the specific operation type —
just "framework workflow X, step Y, elapsed Z".

**Threshold-gating** is a per-consumer policy, not a Sigil
concern. Sigil emits `WorkflowRunStarted` immediately when the
operation begins; clients (Sage's Dart UI) lazy-show the row only
after some elapsed time threshold (e.g., 300ms) so fast workflows
never paint and the activity bar stays clean during normal use.

**What this enables:**
- **Sage activity bar** (above text input field): renders a
  collapsed row per active framework workflow, expandable to show
  per-step progress, cancellable via the bug #51 mechanism.
- **Operator observability**: workflow Notices are the natural
  hook for tracing/metrics/alerting on framework-internal latency.
- **Cancellation surface** (bug #51): every visible workflow has
  a workflowId that the cancel tool / cancel notice can target.
- **Approval flow generalization** (bug #51): the existing
  `WorkflowApprovalRequested` Notice gets reused for framework
  operations that need user consent (e.g., "this `compress` will
  cost $X — approve?").

**Coordination with bug #49 (Provider.maxConcurrent):**
A workflow that's queued waiting for provider capacity should
still emit `WorkflowRunStarted` immediately (so the UI can show
"queued" state), with `WorkflowStepStarted` deferred until the
slot acquires. This makes capacity contention visible without
extra signal types.

**What's NOT in scope:** workflow visualization, threshold gating,
expansion UX — those are application-side concerns (see Sage's
side). Sigil ships the lifecycle; clients render it however
they like.

**Why this is the framework's job:**
Every Sigil consumer with non-trivial latency in framework
operations needs to either render them or accept opaque "Active"
states. The workflow primitive already exists in the framework;
extending its scope to cover internal operations is the right
shape.
