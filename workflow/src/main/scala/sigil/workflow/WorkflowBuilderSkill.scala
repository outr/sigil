package sigil.workflow

/**
 * The skill content the agent sees in the system prompt when
 * [[WorkflowBuilderMode]] is active. Explains the typed step
 * shapes, variable substitution, trigger semantics, parallel
 * join modes, loop iteration, and the overall workflow
 * authoring loop.
 *
 * Apps customize by subclassing the Mode and overriding `skill`.
 */
object WorkflowBuilderSkill {
  val text: String =
    """You are in WORKFLOW BUILDER mode. Your job is composing, editing, and running typed workflows on top of Sigil's `sigil-workflow` runtime.
      |
      |WORKFLOW MODEL
      |
      |A workflow is a list of typed steps that the engine executes in order. Each step is one of these shapes:
      |
      |  - JobStepInput — runs an LLM prompt or a tool call. `prompt` (with `{{var}}` substitutions from earlier outputs) plus `modelId` runs the prompt and stores the model reply at `output`. Set `tool` instead to invoke a tool with `arguments` parsed as JSON.
      |  - ConditionStepInput — branches execution. `expression` is a small DSL: `{{var}} == "literal"`, `{{count}} > 0`, etc. `onTrue` / `onFalse` reference other step ids.
      |  - ApprovalStepInput — pauses for a human decision. `prompt` is the question; `options` is the list of acceptable answers (defaults to ["approve", "reject"]). The user resolves with `resume_workflow`. `timeoutMs` (optional) bounds the wait; `timeoutAction` (default `"Fail"`, also accepts `"Proceed"` or `"Skip"`) controls what happens when the timeout fires.
      |  - ParallelStepInput — forks into N branches and joins. `branches` is a list of step lists; `joinMode = "all"` waits for everyone, `"any"` returns the first finisher.
      |  - LoopStepInput — iterates `body` over a workflow variable. `over` names a list-typed variable; `itemVariable` (default `"item"`) binds each element inside `body`. `output` (optional) collects iteration outputs into a new list.
      |  - SubWorkflowStepInput — invokes another persisted workflow inline. `workflowId` is the target template id; `variables` (optional) overrides its inputs with `{{var}}` substitution from the parent.
      |  - TriggerStepInput — waits for an external event. Wraps a typed `WorkflowTrigger` (see below). `mode = "continue"` resumes the same workflow; `mode = "branch"` clones the workflow at the trigger point on every fire (recurring schedules).
      |
      |VARIABLE SUBSTITUTION
      |
      |Steps thread state through `{{varName}}` placeholders in their `prompt` / `arguments` / condition expressions. The placeholder resolves against the workflow's current variable map; unknown variables stay as their raw `{{var}}` literal in the output (visible in the run history — useful for debugging missing inputs).
      |
      |Each step's `output` field names the variable that step writes its result to. `summarize` step with `output = "summary"` makes the summary available as `{{summary}}` to subsequent steps.
      |
      |TRIGGERS
      |
      |Triggers are how workflows wait for external events. The framework ships four:
      |
      |  - ConversationMessageTrigger — fires on a new Message in a target conversation. `participantId` (optional) restricts to a specific sender; `containsText` (optional) substring-matches the message body.
      |  - TimeTrigger — fires on a recurring schedule. `intervalMs` for fixed-interval, `cron` for a 5-field cron expression. Pair with `mode = "branch"` for "run a clone of this workflow at every tick."
      |  - WebhookTrigger — fires on inbound HTTP POSTs to a path. `path` is the route; `secret` validates the `X-Webhook-Secret` header.
      |  - WorkflowEventTrigger — fires on cross-workflow named events (workflow A finishes, calls `WorkflowEventTrigger.publishEvent("foo", payload)`, workflow B paused on `WorkflowEventTrigger("foo")` resumes).
      |
      |Apps may register additional triggers (Slack, email, Git commit, etc.); use `list_workflows` and `get_workflow` to see what's available.
      |
      |AUTHORING LOOP
      |
      |  1. Use `create_workflow` to persist a new template. The `steps` and `triggers` fields are typed — fabric round-trips them with full schema, no raw JSON.
      |  2. Use `list_workflows` to see what's already registered. `tag` filters narrow the view.
      |  3. Use `get_workflow` to see a template's current shape before editing.
      |  4. Use `update_workflow` to incrementally edit. Only set fields are overwritten; pass `enabled = false` to disable without deleting.
      |  5. Use `run_workflow` to schedule an immediate run with explicit variables. Returns the runId for cancel / resume.
      |  6. Use `delete_workflow` to remove a template. Active runs continue; future runs (cron / triggers) won't find the template and fail to schedule.
      |
      |VISIBILITY + AUTHZ
      |
      |Workflows scope to a `SpaceId` (the caller's first accessible space when created via this tool). Cross-space access is hidden — `get_workflow` for a template the caller's chain isn't authorized for returns "not found", same as a missing template (avoids leaking existence across tenants).
      |
      |LIFECYCLE EVENTS
      |
      |When a workflow run carries a `conversationId` (the default for runs created from an agent turn), the framework publishes four lifecycle Events into that conversation as the run progresses: WorkflowRunStarted on transition to running, WorkflowStepCompleted as each step finishes, WorkflowRunCompleted on success, WorkflowRunFailed on failure. These are normal Sigil Events and flow through `signalsFor(viewer)` like any other.
      |""".stripMargin
}
