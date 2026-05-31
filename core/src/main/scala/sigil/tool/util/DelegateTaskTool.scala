package sigil.tool.util

import fabric.rw.*
import lightdb.id.Id as LId
import rapid.Task
import sigil.conversation.SkillSource
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant, WorkerParticipantId}
import sigil.provider.ToolPolicy
import sigil.signal.EventState
import sigil.tool.model.{DelegateTaskInput, ResponseContent}
import sigil.tool.{RefusalPayload, Tool, ToolContext, ToolExample, ToolName, ToolOutput, ToolResult}

/** Typed result of [[DelegateTaskTool]] — the handle the caller uses to
  * track / drill into the spawned worker. `taskId` is the worker agent's
  * participant id; `workerConvId` is the sub-conversation it runs in. */
case class DelegateTaskOutput(taskId: String,
                              workerConvId: String,
                              role: String) extends ToolOutput derives RW

/**
 * `delegate_task` — spawn a worker as a *real agent in a sub-conversation*
 * (sigil #327, the agent-bridge model). The calling agent creates a
 * sub-conversation `W` (linked to the current conversation as its parent),
 * joins it alongside a freshly-minted worker [[AgentParticipant]], and
 * posts the brief as a Message addressed to the worker — which fires the
 * worker's first canonical agent turn.
 *
 * The calling agent stays a member of `W` as the worker's *supervisor*
 * (its effective "user"): a [[WorkerSupervisorSkill]] slot is activated on
 * the caller's projection in `W` so that, when the worker responds (its
 * turn settles to Idle), the supervisor judges whether the brief is
 * satisfied — posting a follow-up if not, answering the worker's
 * questions, escalating to the human via `relay_message` into the parent
 * conversation when needed, and relaying the result up when done.
 *
 * No workflow run, no hand-rolled ReAct loop: both sides run the canonical
 * agent turn, so transcript persistence, mode/topic drain, triggering, and
 * framing all happen the normal way. Completion is not a special signal —
 * a worker is finished when its `AgentState` settles to Idle, exactly like
 * any agent that has stopped responding.
 *
 * The worker's roster is role-scoped: it gets framework essentials
 * (`respond` to talk to its supervisor, `find_capability`) plus the
 * explicit `toolNames`, NOT the caller's user-facing control surface
 * (`change_mode`, `respond_options`, …). It inherits the parent's
 * workspace via `parentConversationId` (sigil #325).
 */
case object DelegateTaskTool extends Tool {
  type Input  = DelegateTaskInput
  type Output = DelegateTaskOutput
  val inputRW  = summon[RW[DelegateTaskInput]]
  val outputRW = summon[RW[DelegateTaskOutput]]
  val name = ToolName("delegate_task")
  val description =
    """Spawn a worker agent for long-running or specialized work. The worker runs as a real agent in
      |its own sub-conversation linked to this one; you stay in that sub-conversation as its supervisor
      |(its "user") — you task it, answer its questions, and decide what to surface back here. Requires
      |`role` (worker's identity + workType) and `brief` (the directive). `modelId` is optional (omit to
      |let the framework route by `role.workType`). `toolNames` is the worker's work roster (it always
      |gets `respond` + `find_capability`). Returns the worker's id + sub-conversation id.
      |Use for "research X", "build Y", "analyze Z" — anything you'd rather hand off than answer inline.""".stripMargin
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
        goal = Some("identify candidate sources for a literature review")
      )
    )
  )
  override val keywords = Set("delegate", "worker", "spawn", "task", "research", "background", "subagent")

  override def executeResult(input: DelegateTaskInput, ctx: ToolContext): Task[ToolResult[DelegateTaskOutput]] = {
    // Validate `input.modelId` at the boundary so an unknown id refuses
    // here (actionably) rather than failing the worker's first turn after
    // the sub-conversation already exists.
    val supervisorOpt = ctx.conversation.participants.collectFirst {
      case a: AgentParticipant if a.id == ctx.caller => a
    }
    (input.modelId, supervisorOpt) match {
      case (Some(explicit), _) if ctx.sigil.cache.findTolerant(LId[Model](explicit.toLowerCase)).isEmpty =>
        Task.pure(unknownModelRefusal(explicit, ctx))
      case (_, None) =>
        Task.pure(ToolResult.failure(
          message = "delegate_task must be called by an agent participant of this conversation — " +
            "the caller becomes the worker's supervisor and must be able to act in the worker conversation.",
          hint = Some("Only an AgentParticipant can delegate; the supervisor bridges the worker to the user.")
        ))
      case (_, Some(supervisor)) =>
        spawnWorker(input, ctx, supervisor)
    }
  }

  private def spawnWorker(input: DelegateTaskInput,
                          ctx: ToolContext,
                          supervisor: AgentParticipant): Task[ToolResult[DelegateTaskOutput]] = {
    val host         = ctx.sigil
    val parentConvId = ctx.conversation.id
    val workerLabel  = s"Worker: ${input.role.name}"

    val resolvedModelTask: Task[LId[Model]] = input.modelId match {
      case Some(explicit) =>
        Task.pure(host.cache.findTolerant(LId[Model](explicit.toLowerCase)).map(_._id).getOrElse(LId[Model](explicit)))
      case None =>
        host.routedModelFor(
          workType   = input.role.workType,
          chain      = ctx.chain,
          fallback   = ctx.modelId,
          complexity = input.complexity
        )
    }

    val workerId   = WorkerParticipantId(s"${input.role.name}-${rapid.Unique()}")
    val workerTools = input.toolNames.map(ToolName(_))
    val brief       = composeBrief(input)

    for {
      resolvedModel <- resolvedModelTask
      workerAgent = DefaultAgentParticipant(
        id        = workerId,
        modelId   = resolvedModel,
        toolNames = workerTools,
        tools     = ToolPolicy.Standard,
        workType  = input.role.workType,
        roles     = List(input.role)
      )
      // The sub-conversation: supervisor (the delegating agent) + worker,
      // linked to the parent so the worker inherits its workspace (#325)
      // and the supervisor can relay between the two.
      workerConv <- host.newConversation(
        createdBy            = ctx.caller,
        label                = workerLabel,
        summary              = input.goal.getOrElse(brief).take(80),
        participants         = List(supervisor, workerAgent),
        parentConversationId = Some(parentConvId)
      )
      // Activate the supervisor bridge guidance on the caller's projection
      // in the worker conversation (renders only while it acts there).
      _ <- host.activateSkill(
        conversationId = workerConv._id,
        participantId  = ctx.caller,
        source         = SkillSource.Supervisor,
        slot           = WorkerSupervisorSkill.slot(brief, parentConvId, input.role.name)
      )
      // Post the brief addressed to the worker → fires its first turn.
      // From the supervisor's own id, so it doesn't wake the supervisor.
      _ <- host.publish(Message(
        participantId  = ctx.caller,
        conversationId = workerConv._id,
        topicId        = workerConv.currentTopicId,
        content        = Vector(ResponseContent.Text(brief)),
        state          = EventState.Complete,
        role           = MessageRole.Standard,
        addressees     = Some(Set(workerId))
      ))
    } yield ToolResult.Success(DelegateTaskOutput(
      taskId       = workerId.value,
      workerConvId = workerConv._id.value,
      role         = input.role.name
    ))
  }

  /** Build the actionable refusal returned when `input.modelId` is set to
    * an id the host doesn't know about. Visible for testing; tools with
    * the same `modelId` validation shape call this so the format stays
    * consistent. */
  private[util] def unknownModelRefusal(supplied: String, ctx: ToolContext): ToolResult.Failure = {
    val registered = ctx.sigil.cache.all
    val sample = registered.iterator.map(_._id.value).toList.sorted.take(20)
    val sampleBlock =
      if (sample.isEmpty) "<no models registered>"
      else if (registered.size <= sample.size) sample.mkString(", ")
      else sample.mkString(", ") + s" (+${registered.size - sample.size} more)"
    val hint =
      s"`modelId` is optional. Recommended default: omit it and the framework's `ProviderStrategy` " +
        s"picks a candidate based on your `role.workType` and `complexity` (when set). " +
        s"To pin a specific model, supply a `modelId` known to the host's ModelRegistry — sample: $sampleBlock."
    RefusalPayload.schemaMismatch(
      tool = DelegateTaskTool,
      rule =
        s"`delegate_task` rejected: `modelId = \"$supplied\"` is not in the host's ModelRegistry. " +
          "The framework can't route the worker to an unknown model.",
      hint = Some(hint)
    )
  }

  /** Prepend the goal to the worker's brief when set — the worker sees
    * both the high-level intent and the detailed directive. */
  private def composeBrief(input: DelegateTaskInput): String =
    input.goal match {
      case Some(g) if g.nonEmpty => s"Goal: $g\n\n${input.brief}"
      case _                     => input.brief
    }
}
