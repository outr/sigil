# 💡 #391 (proposal) — Container steps (`Loop` / `Parallel` / `Condition`) should *own* their body steps inline, not reference them by id from the flat list

**Status:** design proposal (not a defect report). Addresses the root cause behind the recurring authoring failures #382 / #384 and the latest "write step gets `{}`" run.

**Where:** the model-facing workflow authoring schema — `LoopStepInput.bodyStepIds`, `ParallelStepInput.branchStepIds`, `Condition` branch ids, `WorkflowStepSpec`, the `create_workflow` schema rendering, and the Strider executor's body resolution.

## The recurring problem

The authoring shape is a **flat list of steps**, where a `Loop` names its body by **id reference** (`bodyStepIds: ["read","edit","write"]`) and the referenced steps live elsewhere in the same flat list. Two failure modes fall out of this, and the planner hits them repeatedly:

1. **A body step gets omitted from `bodyStepIds`.** It then executes *outside* the loop's item scope, so `{{itemVariable}}` and any sibling body output resolve to nothing → its tool call receives `{}`.
2. **`bodyStepIds` references an id that doesn't match the intended step** (dangling / wrong id), so the body that runs isn't the body the author meant.

Latest live repro (`sigil-remove-bug-refs`, 06/18):
```
{ id: "loop-files",   kind: Loop, over: "files", itemVariable: "file",
                      bodyStepIds: ["read-file", "edit-file", "write-file"] }   // no persist-file
{ id: "write-file",   kind: Job, prompt: "…clean the edited content…", output: "cleanContent" }
{ id: "persist-file", kind: Job, tool: "write_file",
                      arguments: {"path": "{{file}}", "content": "{{cleanContent}}"} }   // the REAL disk write
```
`persist-file` (the actual `write_file`) is **not** in `bodyStepIds`, so `{{file}}` and `{{cleanContent}}` were unbound → the args collapsed to `{}` → `Unable to find field WriteFileInput.path … in {}`, **every iteration**. The run wrote nothing while reporting progress. (#382 was the same family with a different surface; #384 added authoring-time validation to *catch* it, but a step referencing the loop's itemVar from outside the body still slipped through.)

The deeper issue: an LLM is being asked to keep a **graph consistent by cross-referencing ids**, which it does unreliably — and nothing about the shape makes the mistake unrepresentable.

## Proposal — containers own their children

Make container steps carry their body **inline / nested** instead of by id:

```
{ id: "loop", kind: Loop, over: "hits", itemVariable: "item",
  body: [
    { id: "read",  kind: Job, tool: "read_file",  arguments: {"path": "{{item}}"}, output: "content" },
    { id: "edit",  kind: Job, prompt: "…output the final edited file…",            output: "edited" },
    { id: "write", kind: Job, tool: "write_file", arguments: {"path": "{{item}}", "content": "{{edited}}"} }
  ]
}
```
versus today's flat `bodyStepIds: ["read","edit","write"]` + three sibling steps.

Apply the same to the other containers: `Parallel { branches: [...] }`, `Condition { whenTrue: [...], whenFalse: [...] }`.

### Why this fixes it structurally (not just nudges)
- **The orphaned-body-step failure mode becomes unrepresentable.** A `write_file` physically inside `loop.body` cannot be "outside" the loop's scope. You can't forget to "include" a step that's already there.
- **No dangling ids.** The body *is* the steps, not references to them — the wrong-id mode disappears.
- **Variable scope reads off the structure.** Anything in `body` sees `itemVariable` + earlier siblings' outputs; anything outside plainly doesn't. The model emits a tree that mirrors execution instead of a flat list it must keep internally consistent.

### Keep what made flat attractive
The flat list (#364/#373) kept the schema shallow and every step a uniform shape — easy to emit and validate. This proposal preserves that for **leaf** steps: only the few **container** kinds nest; `Job` stays exactly as it is. So the shape is "a list of top-level steps, where container steps own their children" — leaves stay simple, structure becomes explicit. Top-level sequencing (discovery → loop → compile → report) is still a flat list.

### Migration
- Accept the nested form in `create_workflow`; keep `bodyStepIds` / `branchStepIds` working as a deprecated alias (or auto-lift referenced ids into `body` at parse time) so existing templates don't break.
- Lead the `create_workflow` schema description and examples with the nested form.
- Keep #384's cross-scope validation as a backstop for any residual id-reference path, but with nesting it should rarely fire.

### Scope of change
`LoopStepInput` / `ParallelStepInput` / the `Condition` branches gain an inline `body` (list of `WorkflowStepSpec`); the `create_workflow` schema renders it; `AbstractWorkflowManager` resolves the body from the nested steps instead of looking ids up in the flat list. Persisted `WorkflowTemplate` shape gains the nested representation (with the alias for back-compat).

**Net:** the planner stops being able to mis-structure the loop, because the structure it writes *is* the structure that runs — no id bookkeeping in between.
