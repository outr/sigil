# ❌ #5 — Strider queue doesn't advance to steps appended via `updateSteps` mid-execute

**Where:**
- `core/src/main/scala/sigil/workflow/SigilAgentDecisionStep.scala:252` —
  `appendNextIteration` calls `manager.updateSteps(workflow._id,
  workflow.steps ++ compiled.steps)` to extend a running workflow with the
  next iteration's step (and trigger when suspending on AskParent).
- `/home/mhicks/projects/open/strider/src/main/scala/strider/AbstractWorkflowManager.scala:983`
  — `updateSteps` rebuilds the queue as `newSteps.map(_.id).filterNot(wf.completed.contains)`
  inside a `modify`.

**What's wrong:** A multi-iteration worker run never advances past its
first `AgentDecisionStep`. The step settles cleanly with `asked: true`
(or `complete: false` for plain ReAct continuation) and `updateSteps`
returns OK, but Strider's runner then treats the workflow as finished —
no second iteration fires, no AnswerTrigger registers.

**How discovered:** `core/src/test/scala/spec/LlamaCppWorkerSpec.scala`
has a single-shot live test that passes (worker emits `Complete:`
immediately, run terminates). The follow-on AskParent end-to-end test
(worker iter 1 emits `AskParent:` → suspends → test code publishes
`WorkerAnswer` → worker iter 2 emits `Complete: <decision>`) hangs
indefinitely waiting for the run to settle. The trigger never registers
because the workflow never advances to the trigger step.

The AskParent test was reverted from the spec; the failure log is
preserved if you want to repro: same shape as `LlamaCppWorkerSpec`'s
existing test but with a brief that forces the LLM to emit
`AskParent:` first.

**Root cause (after investigation):** LightDB transaction isolation.

Strider's `executeWorkflow` runs inside one long-lived transaction
(`txn`) that the runner threads through `recurseWorkflow` →
`executeJob` → step's `execute`. Reads on that transaction see its
own snapshot.

Our `updateSteps`-from-execute call opens a **separate** transaction:

```scala
def updateSteps(workflowId, newSteps): Task[Workflow] =
  collection.transaction { txn =>  // NEW transaction
    modify(workflowId, txn) { wf => ... }
  }
```

It writes the new queue and commits. But the runner's long-lived
transaction is still alive and reads its own snapshot — which has
the OLD queue (just the original step). After the current step
settles, the runner's `recurseWorkflow` checks
`workflow.queue.nonEmpty` against the snapshot, sees it empty, and
returns. `executeWorkflow` (line 497) then runs:

```scala
addHistory(workflow._id, WorkflowActivity.Completed(true), txn)
  .when(!wf.finished && wf.waitingStepId.isEmpty)
```

The workflow gets marked Completed(true) before the new steps are
even visible. Game over.

The single-shot Complete: case works because the worker terminates
INSIDE the first execute (no `updateSteps` call) — the runner's
"queue is empty, complete the workflow" path is correct for that
shape.

**Fix avenues:**

  - **Pass the runner's `txn` into the step's execute** — Strider
    would need to extend `Job[T].execute(workflow, pm)` to
    `execute(workflow, pm, txn)`. The step could then call a
    `manager.updateStepsIn(txn, runId, newSteps)` overload that
    uses the SAME transaction. Real Strider API surface change.
  - **Don't use updateSteps from execute; use a runner hook
    instead.** Have `AgentDecisionStep` return a special "append
    these steps" payload, and override `onStepCompleted` in
    `SigilWorkflowManager` to detect the payload + call updateSteps
    AFTER the runner's transaction completes. But onStepCompleted
    runs inside `flatTap` between modify and recurseWorkflow —
    same transaction, same issue.
  - **Run AgentDecisionStep iterations as separate workflow runs
    chained via SubWorkflow / Trigger.** Each iteration is its own
    run that schedules the next one on completion. Heavier shape;
    each iteration loses access to the ambient run state (variable
    bag, payloads, etc.).
  - **Fix at Strider layer** by making the runner re-read the
    workflow row after each step. Adds a DB read per step but
    makes mid-execute mutations honor-able.

I lean on the first option (txn parameter on execute) — most
direct fix, smallest API surface change, makes the architectural
intent explicit ("steps can mutate the workflow within their own
execution boundary"). It's a Strider release coupled with a Sigil
release; ~half-day of work spread across both repos.

**Suggested fix:** Investigate Strider's queue-iteration logic. Possible
work breakdown:

  1. Add a Strider-level test: schedule a workflow with one Job that
     calls `manager.updateSteps(thisRun, currentSteps :+ NextJob)` and
     verify NextJob runs. This isolates the Strider behavior from the
     AgentDecisionStep / signal subscription complexity.
  2. If the Strider test reproduces the problem, fix at that layer
     (Strider). If not, the issue is Sigil-side — likely in how the
     re-fired AgentDecisionStep iteration's input reads the workflow
     state.
  3. Once the queue-advance works, restore the AskParent live test
     from the reverted commit and verify end-to-end.

**Until this fix lands:** the worker delegation surface is a
single-iteration tool. Multi-iteration ReAct, AskParent suspend/resume,
and Report:/Status: marker chaining all rely on the
`updateSteps`-from-execute pattern and won't actually iterate.
Single-shot worker (`Complete:` on first turn) DOES work — the
`LlamaCppWorkerSpec` Complete: test exercises this and passes.

**Adjacent unit tests that still pass independently:**

- `AgentDecisionStepLogicSpec` — parser + prompt-builder + parent-answer
  threading logic (the LOGIC works; just the multi-step Strider
  orchestration doesn't run live).
- `AnswerTriggerSpec` — `WorkerAnswer` Notice + `answer_worker` tool
  publishing through Sigil's signal stream.
- `TaskExecutedSpec`, `TotalCostForSpec`, `ConversationHierarchySpec`,
  `ActiveTasksSpec` — supporting types and projections.

The worker delegation architecture is structurally sound; the
remaining gap is one Strider integration concern that an ~hour
of focused debugging should resolve.
