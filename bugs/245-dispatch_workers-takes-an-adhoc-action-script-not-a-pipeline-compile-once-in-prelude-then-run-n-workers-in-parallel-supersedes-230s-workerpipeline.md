# 💡 #245 — `dispatch_workers` takes an adhoc `action` script (not a `pipeline`); compile once in prelude, then run N workers in parallel. Supersedes #230's `WorkerPipeline`.

**Status:** Design note. Architectural simplification. Supersedes
the `WorkerPipeline { llm: Option, script: Option }` shape from
#230.

**Where (proposed):**
- `tooling/src/main/scala/sigil/tooling/dispatch/DispatchWorkersTool.scala`
  — replace the existing `WorkerPipeline` field with a single
  `action: String` (the adhoc Scala script).
- `tooling/src/main/scala/sigil/tooling/dispatch/DispatchWorkersInput.scala`
  — collapse pipeline shape down to `action`.
- `tooling/src/main/scala/sigil/script/ScriptEvaluator.scala`
  (or wherever `execute_script` compiles + runs) — expose a
  `compile(source) → CompileResult` entry point so the
  dispatcher can pre-flight before spawning workers.

**Motivation:**

Three rounds of refinement to dispatch_workers' worker shape
across this session converged on the same insight: workers are
adhoc, one-shot, and the agent already constructs them right
when they're inline Scala. The progression:

1. **#230's `WorkerPipeline { llm: Option, script: Option }`** —
   too flexible; the LLM-only case has no tool access, the LLM-
   then-script case is awkward, and the agent in field evidence
   constructed only the LLM-only form (which then can't read
   files). See Sage 2026-05-19 12:09:32: worker LLM asked to
   "read the file" with no tool access, spent 30-50s reasoning,
   asked the user to paste contents.
2. **Mini-agent workers (briefly considered)** — workers as full
   agent runs with tool rosters. Maximum flexibility but maximum
   cost (LLM agent loop per item) and least predictable.
3. **Pre-registered script tool (`create_script_tool` + dispatch
   referencing tool name)** — clean composition but adds tool-
   registration ceremony for a one-shot use case. `create_script_tool`
   is for PERSISTENT registered tools; dispatch_workers' action is
   adhoc by nature.
4. **Adhoc `action: String`** (this design) — matches the
   `execute_script` shape. Agent inlines the script; dispatcher
   compiles once, runs N times in parallel; cost and behavior
   are explicit in the script's body.

**Suggested design:**

```scala
case class DispatchWorkersInput(
  itemsId: Id[ToolOutputNode],   // per #244 — container of items
  /** Scala action script executed once per group. The group's items
    * are bound as `items: List[Json]` (length == groupSize, except
    * possibly smaller for the final group). Same evaluator / sandbox /
    * tool-callable surface as `execute_script` — including
    * `llmCall(prompt, model?, schema?)` for any LLM-judgment the
    * script needs. The script's last expression is the per-group
    * worker result.
    *
    * The dispatcher compiles `action` once before spawning workers
    * (see CompileFailure below). If compile fails, no workers run
    * and the agent gets typed errors to fix. If it succeeds, the
    * compiled artifact is shared across workers. */
  action: String,
  /** Items per worker invocation. With the default groupSize=1, each
    * worker processes one item (and `items` is a single-element list).
    * Higher values batch items into one worker invocation — the
    * script handles batching semantics internally (e.g., one
    * `llmCall` per group instead of one per item). Cost preview
    * reports `ceil(N / groupSize)` worker invocations. */
  groupSize: Int = 1,
  maxParallel: Int = 5,
  maxItems: Int = 10000,
  confirmed: Boolean = false
)
// Validation: groupSize >= 1; itemsId resolves; action compiles.

// LLM-routing concerns (which model, what schema, whether to call at
// all) live INSIDE the action script via `llmCall(prompt, model?, schema?)`.
// The framework doesn't need a separate prompt / outputSchema / workerModelId
// field — `llmCall` covers every case the framework-managed prompt would,
// plus conditional invocation, multiple calls per group, cheap-first-pass-
// then-LLM-only-on-ambiguous patterns, etc.

sealed trait DispatchWorkersOutput derives RW
object DispatchWorkersOutput {
  /** Pre-flight compile failed; no workers ran. Returns typed errors
    * (line, column, message) so the agent can fix and retry without
    * a separate execute_script round-trip. */
  case class CompileFailure(
    errors: List[CompileError]
  ) extends DispatchWorkersOutput derives RW

  /** Scope-preview result for `confirmed=false`. Action compiled OK,
    * no workers ran. Per #243 — discriminated output so the agent
    * can't confuse with a completed dispatch. */
  case class ScopePreview(
    sessionId:        String,
    itemCount:        Int,
    actionPreview:    String,     // first ~200 chars for sanity-check
    compileOk:        Boolean,    // always true at this point; here
                                   // for symmetry with CompileFailure
    confirmCall:      String      // human-readable directive
  ) extends DispatchWorkersOutput derives RW

  /** Actual dispatch result for `confirmed=true`. */
  case class DispatchResult(
    sessionId: String,
    perItem:   List[WorkerOutcome]
  ) extends DispatchWorkersOutput derives RW
}

case class CompileError(
  line:    Int,
  column:  Int,
  message: String
) derives RW

case class WorkerOutcome(
  itemIndex: Int,
  result:    Either[String, Json]   // Left = runtime error message; Right = script's return value
) derives RW
```

### Dispatcher flow

```
1. Resolve items from itemsId (read ToolOutputNode subtree)
   → fail with NotFound if container expired / never existed
2. Compile `action` once via ScriptEvaluator.compile
   → on compile error: return CompileFailure(errors), exit
3. If confirmed=false:
   → return ScopePreview(itemCount, actionPreview, compileOk=true, confirmCall)
   → exit
4. If confirmed=true:
   → spawn workers using parSequenceBounded(parallelism=maxParallel)
   → each worker invokes the compiled closure with item=<this item's payload>
   → collect per-item results (Either runtime-error Json)
   → return DispatchResult
```

### Why pre-flight compile matters

Field evidence — Sage 2026-05-19 08:21:52 — agent emitted four parallel
tool calls with `"string"` placeholder values, INCLUDING a dispatch_workers
call with `pipeline.script.code: "string"`. If the dispatcher had compiled
"string" as Scala source, it would have failed immediately with a typed
CompileFailure pointing at the obviously-wrong code. Instead the agent's
choice rippled through and the framework had to handle the resulting empty-
worker case downstream.

Generally: action scripts that don't compile shouldn't dispatch ANY
workers. The cost of N parallel identical compile failures (each worker
re-discovers the same error) is wasted scheduling, wasted log noise, and
N times the latency to surface the error to the agent. Pre-flight catches
it once.

### Side benefit: cache compiled artifact across workers

Compiling a 100-line Scala block takes ~100-300ms. With `maxParallel=10`
and `itemCount=1000`, parallel compilation in each worker would burn
significant time. A shared compiled closure cuts that to ~one compile
total. The evaluator only needs to expose `compile(src) → Compiled` then
`Compiled.invoke(item: Json) → Json` for parallel-safe reuse.

### LLM-judgment within actions

For tasks that legitimately need per-item LLM reasoning, the action
script calls into Sigil's existing per-script LLM primitive (whatever
`execute_script` exposes today — likely a `llmCall(prompt, model, schema)`
helper). The agent controls EXACTLY where LLM time gets spent:

```scala
// Agent's action for "remove bug comments preserving valuable docs":
val filePath = item("filePath").asString
val content  = readFile(filePath)
val matches  = item("matches").asVector

// Pure-Scala for mechanical cases:
val mechanicalEdits = matches.collect {
  case m if isSimpleBugMarker(m.lineText) => removeLineEdit(m)
}

// LLM only for ambiguous cases:
val judgmentEdits = matches.collect {
  case m if isAmbiguousBugReference(m.lineText) =>
    llmCall(
      prompt = s"Is this comment safe to remove? ${context(m, content)}",
      model = "kimi-k2.5",
      schema = booleanSchema
    ) match {
      case Right(true) => Some(removeLineEdit(m))
      case _           => None
    }
}.flatten

applyEdits(filePath, mechanicalEdits ++ judgmentEdits)
```

LLM cost is proportional to ambiguous cases, not total items. For a 1000-
item refactor where 95% are unambiguous, that's a 20× cost reduction over
the mini-agent shape.

### What drops vs. previous designs

| #230 / earlier #245                        | This design               |
|--------------------------------------------|---------------------------|
| `WorkerPipeline { llm, script }`           | `action: String`          |
| `LlmStep { prompt, systemPrompt, schema }` | (gone — inline in action) |
| `ScriptStep { code, language, allowedTools }` | (gone — action IS the script) |
| `complexity: Complexity` (required)        | (gone — action picks model per `llmCall`) |
| `workerModelId: Option[String]`            | (gone — action picks model per `llmCall`) |
| Worker tool roster management              | (gone — action uses execute_script's tool surface) |
| Mini-agent iteration cap                   | (gone — action is straight-line code) |

### Workflow end-to-end

```
agent: grep(pattern, glob, path)              → C1 (container, per #244)

agent: execute_script(code = "<action code>",
                      input = sampleItem)     → verify action works on one item
       ↑ optional but recommended

agent: dispatch_workers(itemsId=C1,
                        action="<action code>",
                        confirmed=false)      → ScopePreview {
                                                   itemCount: 47,
                                                   actionPreview: "val filePath = item(...)...",
                                                   compileOk: true,
                                                   confirmCall: "call again with confirmed=true"
                                                 }

agent: dispatch_workers(itemsId=C1,
                        action="<same code>",
                        confirmed=true)       → DispatchResult { perItem: [...] }
```

Four clean steps. Each cheap, each inspectable. Per-step costs are
explicit (grep is small, execute_script is one run, ScopePreview is
metadata-only, dispatch is the bulk).

### Failing tests

Under `core/src/test/scala/spec/DispatchWorkersActionSpec.scala`:

1. **Pre-flight compile catches bad scripts before dispatch:**
   - Call with `action = "this is not valid scala"`, `confirmed=true`.
   - Assert: result is `CompileFailure(errors)` with at least one
     CompileError that has a non-empty message.
   - Assert: NO workers were dispatched.

2. **`confirmed=false` returns ScopePreview after successful compile:**
   - Valid action script.
   - Assert: result is `ScopePreview`, NOT `DispatchResult`.
   - Assert: `compileOk == true`.
   - Assert: NO workers ran.

3. **`confirmed=true` runs the action per item with `item` bound:**
   - itemsId has 5 items.
   - action: `item("name").asString.toUpperCase`.
   - Assert: result is `DispatchResult` with 5 outcomes.
   - Assert: each outcome's `result` is `Right(JString(uppercased name))`.

4. **Compiled artifact is shared across workers (not re-compiled):**
   - Spy on the script evaluator's compile call.
   - Run dispatch with 100 items, maxParallel=10.
   - Assert: `compile` was called exactly ONCE.

5. **Runtime errors in one worker don't fail others:**
   - 5 items; action: `if (item("name").asString == "boom") throw new RuntimeException("explode") else item("name").asString`.
   - Assert: 4 outcomes are `Right(...)`; one is `Left("explode")`.
   - Assert: NO global dispatch failure.

All five must FAIL on current SNAPSHOT; pass after the fix.

**Related:**
- **#230** — original dispatch_workers design with `WorkerPipeline`.
  This bug supersedes the pipeline shape entirely.
- **#243** — ScopePreview discriminated output. Kept; just the
  preview's content shrinks (`compileOk` flag is the meaningful
  addition).
- **#244** — container model for `itemsId`. Independent; this bug
  inherits the container input shape unchanged.
- **`execute_script`** — provides the evaluator, sandbox, tool
  surface, and (likely) `llmCall` primitive that actions reuse.
  dispatch_workers is a parallel-map wrapper around it.
- **Sage 2026-05-19 12:09:32** — field evidence of the LLM-only
  pipeline failure (worker asked to read file with no tools).
  This design eliminates the failure mode.
- **Sage 2026-05-19 08:21:52** — field evidence of the agent
  emitting `pipeline.script.code: "string"`. Pre-flight compile
  catches this kind of garbage before any worker runs.
