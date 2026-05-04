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

**What I think is happening:** Inside the current step's `execute`,
`updateSteps` runs and rebuilds the queue. At that moment the current
step is NOT in `wf.completed` yet (we're still inside execute), so the
new queue includes the current step's id at the front — `[currentId,
TriggerId, NextDecisionId]`. After the step settles, Strider:

  1. Adds `currentId` to `completed`.
  2. Picks the next step from `queue`. If it pops the front (which is
     `currentId`, now in completed) and treats it as "done, don't run
     again", then it might also conclude the workflow has no remaining
     work and finish.

OR: Strider's runner stamps the workflow's queue snapshot when it
begins a step and ignores mid-execute mutations until the step
finishes. So `updateSteps`'s queue change is effectively dropped.

Either way, the real fix is one of:

  - **Filter the current step's id out of the new queue ourselves**:
    `newSteps.map(_.id).filterNot(_ == currentStepId).filterNot(wf.completed.contains)`
    so the queue Strider re-reads only contains genuinely-pending ids.
    (Belt-and-suspenders; doesn't help if Strider snapshots the queue.)
  - **Defer the `updateSteps` call to AFTER the step's `execute`
    returns**. Strider would need a hook for "I'm settling and want to
    append these steps next." The closest existing hook is
    `manager.onStepCompleted`, but that's fire-and-forget — doesn't
    accept new step inputs.
  - **Inspect Strider's runner more carefully**. Maybe there's an
    existing affordance — `runNextScheduled` / queue-pop semantics —
    that we just need to use correctly. The unit-tested
    `AbstractWorkflowManager#updateSteps` IS designed for live edits
    (it has `propagateChanges` / `resolveConflict` for the case where
    completed steps changed). Maybe the issue is just that the
    in-execute call interleaves badly with the runner's loop.

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
