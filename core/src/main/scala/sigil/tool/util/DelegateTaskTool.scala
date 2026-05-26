package sigil.tool.util

import fabric.rw.*
import lightdb.id.Id as LId
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.DelegateTaskInput
import sigil.tool.{Tool, ToolExample, ToolName, ToolOutput, ToolResult}
import sigil.workflow.{AgentDecisionStepInput, WorkflowSigil, WorkflowStepInputCompiler}
import sigil.workflow.SigilWorkflowModel.stepRW
import strider.WorkflowParent

/** Typed result of [[DelegateTaskTool]] — the handle the caller
  * uses to track / drill into the spawned worker's run. */
case class DelegateTaskOutput(taskId: String,
                              workerConvId: String,
                              role: String) extends ToolOutput derives RW

/**
 * `delegate_task` — spawn a worker. Creates a scratchpad
 * conversation linked back to the current conversation, schedules
 * a workflow run whose initial step is one
 * [[AgentDecisionStepInput]] carrying the supplied role + brief +
 * resolved modelId, and returns the run id so the caller can
 * track / drill into the worker's transcript.
 *
 * Requires the host Sigil to mix in [[WorkflowSigil]] — the tool
 * looks up the framework's `workflowManager` to schedule the run.
 * Apps that don't have the workflow runtime active see a logical
 * failure rather than a crash.
 *
 * v1 ships single-iteration workers (the AgentDecisionStep runs
 * once and the run completes). Multi-iteration ReAct (where the
 * agent's tool calls translate to additional appended steps) is
 * the phase 2 follow-on; this tool's wire shape doesn't change
 * when that lands.
 */
case object DelegateTaskTool extends Tool {
  type Input  = DelegateTaskInput
  type Output = DelegateTaskOutput
  val inputRW  = summon[RW[DelegateTaskInput]]
  val outputRW = summon[RW[DelegateTaskOutput]]
  val name = ToolName("delegate_task")
  val description =
    """Spawn a worker for long-running or specialized work. Worker runs in its own scratchpad
      |conversation linked to this conversation. Requires `role` (worker's identity + workType),
      |`brief` (the directive), `modelId` (the resolved model). Returns the worker's task/run id.
      |Use for "research X", "build Y", "analyze Z" — anything you'd rather hand off than
      |answer inline.""".stripMargin
  override val examples = List(
    ToolExample(
      "Delegate a research task",
      DelegateTaskInput(
        role = sigil.role.Role(
          name = "researcher",
          description = "You are a research agent. Find relevant sources, synthesize, and report.",
          workType = sigil.provider.AnalysisWork
        ),
        brief = "Find recent papers on retrieval-augmented generation in 2026.",
        goal = Some("identify candidate sources for a literature review"),
        modelId = Some("anthropic/claude-sonnet-4-6")
      )
    )
  )
  override val keywords = Set("delegate", "worker", "spawn", "task", "research", "background", "subagent")

  override def executeResult(input: DelegateTaskInput,
                             ctx: ToolContext): Task[ToolResult[DelegateTaskOutput]] =
    ctx.sigil match {
      case ws: WorkflowSigil => spawnWorker(ws, input, ctx)
      case _ =>
        Task.pure(ToolResult.failure(
          "delegate_task requires the host Sigil to mix in WorkflowSigil; the workflow runtime is not active."))
    }

  private def spawnWorker(ws: WorkflowSigil & sigil.Sigil,
                          input: DelegateTaskInput,
                          ctx: ToolContext): Task[ToolResult[DelegateTaskOutput]] = {
    val workerLabel  = s"Worker: ${input.role.name}"
    val parentConvId = ctx.conversation.id

    // Sigil #289 — resolve the worker's model:
    //   1. If `input.modelId` is set, use that exact model (most explicit).
    //   2. Else route via `Sigil.routedModelFor` with `role.workType`
    //      + optional `complexity` filter, falling back to the
    //      spawning agent's modelId.
    val resolvedModelTask: Task[String] = input.modelId match {
      case Some(explicit) => Task.pure(explicit)
      case None =>
        ws.routedModelFor(
          workType   = input.role.workType,
          chain      = ctx.chain,
          fallback   = ctx.modelId,
          complexity = input.complexity
        ).map(_.value)
    }

    // Sigil #289 — when `toolNames` is empty, inherit the spawning
    // agent's effective roster. Lets agents say "delegate this with
    // my own capabilities" without re-enumerating tool names.
    val effectiveToolNames: List[String] =
      if (input.toolNames.nonEmpty) input.toolNames
      else inheritParentRoster(ws, ctx)

    val maxIter = input.maxIterations.getOrElse(50)

    for {
      resolvedModel <- resolvedModelTask
      workerConv <- ws.newConversation(
        createdBy             = ctx.caller,
        label                 = workerLabel,
        summary               = input.goal.getOrElse(input.brief).take(80),
        participants          = Nil,
        parentConversationId  = Some(parentConvId)
      )
      stepInput = AgentDecisionStepInput(
        id            = "decision-0",
        name          = Some(s"Worker decision (${input.role.name})"),
        role          = input.role,
        brief         = composeBrief(input),
        modelId       = resolvedModel,
        maxIterations = maxIter,
        toolNames     = effectiveToolNames
      )
      compiled = WorkflowStepInputCompiler.compile(List(stepInput))(using summon[fabric.rw.RW[strider.step.Step]])
      // Synthetic ad-hoc parent id — for inline runs there's no
      // persisted WorkflowTemplate to resolve against. The manager's
      // resolveParent returns None for this id and the lifecycle
      // events surface with a synthetic source. Not worth a real
      // template row per delegation.
      sourceId = LId[WorkflowParent](s"adhoc-${input.role.name}-${rapid.Unique()}")
      // Pass conversationId at schedule time — the prior pattern
      // (schedule, then modify to stamp conversationId) raced with
      // Strider's monitor picking up the freshly-scheduled run; the
      // monitor's `txn.upsert` of its working-snapshot wiped the
      // post-schedule modify, leaving `conversationId = None` at
      // settle time and silently breaking lifecycle attribution.
      run     <- ws.workflowManager.schedule(
        name            = workerLabel,
        steps           = compiled.steps,
        sourceId        = sourceId,
        conversationId  = Some(workerConv._id.value)
      )
    } yield ToolResult.Success(DelegateTaskOutput(
      taskId       = run._id.value,
      workerConvId = workerConv._id.value,
      role         = input.role.name
    ))
  }

  /** Resolve the spawning agent's effective roster — the union of
    * the agent's `tools` policy and the active mode's `tools` policy
    * via `Sigil.effectiveToolNames`. When the agent's participant
    * record can't be located on the conversation (unusual but
    * possible for chain-only callers), fall back to an empty roster
    * — the worker becomes a pure-reasoning agent which is the
    * safer no-tool default. */
  private def inheritParentRoster(host: _root_.sigil.Sigil,
                                  ctx: ToolContext): List[String] = {
    val agentOpt = ctx.conversation.participants.collectFirst {
      case agent: _root_.sigil.participant.AgentParticipant if agent.id == ctx.caller => agent
    }
    agentOpt match {
      case Some(agent) =>
        host.effectiveToolNames(agent, ctx.conversation.currentMode, Nil).map(_.value)
      case None => Nil
    }
  }

  /** Prepend the goal to the worker's brief when set — the worker
    * sees both the high-level intent and the detailed directive.
    * Goal-less briefs pass through unchanged. */
  private def composeBrief(input: DelegateTaskInput): String =
    input.goal match {
      case Some(g) if g.nonEmpty => s"Goal: $g\n\n${input.brief}"
      case _                     => input.brief
    }
}
