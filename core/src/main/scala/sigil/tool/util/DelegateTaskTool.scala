package sigil.tool.util

import fabric.{obj, str}
import lightdb.id.Id as LId
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.{DelegateTaskInput, ResponseContent}
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.workflow.{AgentDecisionStepInput, WorkflowSigil, WorkflowStepInputCompiler}
import sigil.workflow.SigilWorkflowModel.stepRW
import strider.WorkflowParent

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
 * Apps that don't have the workflow runtime active see a tool
 * error message rather than a crash.
 *
 * v1 ships single-iteration workers (the AgentDecisionStep runs
 * once and the run completes). Multi-iteration ReAct (where the
 * agent's tool calls translate to additional appended steps) is
 * the phase 2 follow-on; this tool's wire shape doesn't change
 * when that lands.
 */
case object DelegateTaskTool
  extends TypedTool[DelegateTaskInput](
    name = ToolName("delegate_task"),
    description =
      """Spawn a worker for long-running or specialized work. Worker runs in its own scratchpad
        |conversation linked to this conversation. Requires `role` (worker's identity + workType),
        |`brief` (the directive), `modelId` (the resolved model). Returns the worker's task/run id.
        |Use for "research X", "build Y", "analyze Z" — anything you'd rather hand off than
        |answer inline.""".stripMargin,
    examples = List(
      ToolExample(
        "Delegate a research task",
        DelegateTaskInput(
          role = sigil.role.Role(
            name = "researcher",
            description = "You are a research agent. Find relevant sources, synthesize, and report.",
            workType = sigil.provider.AnalysisWork
          ),
          brief = "Find recent papers on retrieval-augmented generation in 2026.",
          modelId = "anthropic/claude-sonnet-4-6"
        )
      )
    ),
    keywords = Set("delegate", "worker", "spawn", "task", "research", "background", "subagent")
  ) {

  override protected def executeTyped(input: DelegateTaskInput, ctx: TurnContext): Stream[Event] = Stream.force {
    ctx.sigil match {
      case ws: WorkflowSigil =>
        spawnWorker(ws, input, ctx)
      case _ =>
        Task.pure(emit(ctx, obj(
          "ok"    -> str("false"),
          "error" -> str("delegate_task requires the host Sigil to mix in WorkflowSigil; the workflow runtime is not active.")
        )))
    }
  }

  private def spawnWorker(ws: WorkflowSigil & sigil.Sigil, input: DelegateTaskInput, ctx: TurnContext): Task[Stream[Event]] = {
    val workerLabel  = s"Worker: ${input.role.name}"
    val parentConvId = ctx.conversation.id

    for {
      workerConv <- ws.newConversation(
        createdBy             = ctx.caller,
        label                 = workerLabel,
        summary               = input.brief.take(80),
        participants          = Nil,
        parentConversationId  = Some(parentConvId)
      )
      stepInput = AgentDecisionStepInput(
        id        = "decision-0",
        name      = Some(s"Worker decision (${input.role.name})"),
        role      = input.role,
        brief     = input.brief,
        modelId   = input.modelId,
        toolNames = input.toolNames
      )
      compiled = WorkflowStepInputCompiler.compile(List(stepInput))(using summon[fabric.rw.RW[strider.step.Step]])
      // Synthetic ad-hoc parent id — for inline runs there's no
      // persisted WorkflowTemplate to resolve against. The manager's
      // resolveParent returns None for this id and the lifecycle
      // events surface with a synthetic source. Not worth a real
      // template row per delegation.
      sourceId = LId[WorkflowParent](s"adhoc-${input.role.name}-${rapid.Unique()}")
      run     <- ws.workflowManager.schedule(
        name      = workerLabel,
        steps     = compiled.steps,
        sourceId  = sourceId
      ).flatMap(wf =>
        // Stamp the conversationId on the freshly-scheduled run so
        // lifecycle events fire into the worker conv (and the cost
        // projection eventually rolls up via parentConversationId).
        ws.workflowManager.collection.transaction(_.modify(wf._id) {
          case Some(current) => Task.pure(Some(current.copy(conversationId = Some(workerConv._id.value))))
          case None          => Task.pure(None)
        }).map(_ => wf)
      )
    } yield emit(ctx, obj(
      "ok"            -> str("true"),
      "taskId"        -> str(run._id.value),
      "workerConvId"  -> str(workerConv._id.value),
      "role"          -> str(input.role.name)
    ))
  }

  private def emit(ctx: TurnContext, payload: fabric.Json): Stream[Event] =
    Stream.emit[Event](Message(
      participantId  = ctx.caller,
      conversationId = ctx.conversation.id,
      topicId        = ctx.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(fabric.io.JsonFormatter.Compact(payload))),
      state          = EventState.Complete,
      role           = MessageRole.Tool
    ))
}
