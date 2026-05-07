# ❌ #51 — Workflow lifecycle has no cancellation or approval-decision primitives; user-driven control isn't expressible

**Where:**
- `core/src/main/scala/sigil/workflow/` — workflow lifecycle Notices
  (`WorkflowRunStarted`, `WorkflowStepCompleted`,
  `WorkflowRunCompleted`, `WorkflowRunFailed`,
  `WorkflowApprovalRequested`) define a one-way reporting flow:
  framework → clients. There's no return path for clients to
  influence the workflow's execution.
- `WorkflowApprovalRequested` is the closest thing to a decision
  point, but it has no symmetric `WorkflowApprove` / `WorkflowDecline`
  mechanism — the workflow runtime can ask, but there's nowhere
  defined for the answer to come back.

**What's wrong:** Once bug #50 ships and framework workflows
(pre-flight, compress, frame-load, etc.) are visible to users,
they need to be controllable. Two distinct decisions a workflow
needs to accept:

1. **Cancel** — "abandon this operation; fall through to whatever
   degraded path the workflow type defines."
2. **Approve / decline** — for workflows that pause at decision
   points (cost-gated, side-effect-gated), the answer to the
   `WorkflowApprovalRequested` prompt.

Both can come from either:
- The user (clicking buttons in the activity-bar row)
- The agent itself (deciding on its own behalf — "this `compress`
  is too slow, abandon and proceed truncated")

A pure-Notice approach (client→server `CancelWorkflow` Notice,
`ApproveWorkflow` Notice, etc.) works mechanically but misses a
useful property: **the agent doesn't see decisions that didn't
flow through tool calls.** If the user cancels via Notice, the
agent's next turn doesn't see the cancellation in its conversation
context — it has to be reconstructed from missing signals or
out-of-band state.

**Suggested fix — expose cancel/approve/decline as tools, dual-
invokable.**

```scala
case object CancelWorkflowTool extends Tool[CancelWorkflowInput] {
  val name = ToolName("cancel_workflow")
  val description = "Cancel an in-progress workflow by id."
  // Input: workflowId, optional reason
  // Effect: routes to workflow runtime cancellation, which
  // propagates to in-flight HTTP calls / streams / etc., emits
  // WorkflowRunCompleted(outcome = Cancelled) when settled.
}

case object ApproveWorkflowTool extends Tool[ApproveWorkflowInput] {
  val name = ToolName("approve_workflow")
  // Input: workflowId, optional comment
  // Effect: signals the paused workflow runtime to proceed.
}

case object DeclineWorkflowTool extends Tool[DeclineWorkflowInput] {
  val name = ToolName("decline_workflow")
  // Input: workflowId, optional reason
  // Effect: signals the paused workflow runtime to take its
  // declined-branch path; emits WorkflowRunCompleted(outcome
  // = Declined).
}
```

**Dual invocation:**

- **Agent-initiated**: agent calls `cancel_workflow(wf-123)` from
  within its turn loop. Standard tool-call pathway; conversation
  records the call.
- **User-initiated**: Sage's UI synthesizes a tool-call from the
  user side when the user clicks the cancel button on a workflow
  row. The user-attributed tool call lands in the conversation
  the same way; agent's next turn sees "User cancelled workflow
  X" in its context and can react.

This matches Sigil's existing pattern with the `stop` tool —
agent has it as a roster entry for self-stop; the UI Stop button
is a separate mechanism today, but conceptually the same shape
applies (user actions that match a tool's surface should route
through that tool for record-keeping uniformity).

**Per-workflow cancel-fallback semantics** are part of each
workflow type's contract:

- **Pre-flight cancel**: drop to piecewise estimate fallback.
  Trivial — the fallback was already the error-path code path.
- **Compress cancel**: drop older content un-summarized;
  curator's Stage 3 returns over-budget result; Provider's
  pre-flight emergency-shed picks up the excess.
- **Frame-load cancel**: less obviously useful — usually you
  want frames; fail-fast might be the right answer.
- **Application-level workflows**: defined by the workflow's
  author (the script-tool author, the multi-step orchestrator,
  etc.).

The cancel-fallback contract is documented per workflow type;
the tool-call mechanism is uniform.

**What's NOT in scope:**
- The activity-bar UI (Sage-side)
- The cancel button rendering (Sage-side)
- Threshold gating for when to expose the cancel button
  (Sage-side — pure UX policy)

**Why this is the framework's job:**
Cancellation and approval are control-flow primitives of the
workflow runtime. Defining them once at the framework level —
with consistent semantics, conversation-record uniformity, and
agent-visibility — beats having every consumer re-implement
their own out-of-band cancel signals.

**Coordination with bugs #49 + #50:**
- A workflow queued behind `Provider.maxConcurrent` can be
  cancelled before it even acquires its slot — releases the
  slot reservation cleanly.
- A workflow surfaced in Sage's activity bar (per #50) gets
  its cancel button wired to `cancel_workflow` (this bug).
- An approval-required workflow surfaced in the activity bar
  gets Approve/Decline buttons wired to `approve_workflow` /
  `decline_workflow`.

These three bugs (#49 / #50 / #51) compose: capacity, visibility,
control. Together they remove the "opaque slow framework" UX
class without requiring a new layer of out-of-band signaling.
