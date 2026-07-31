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
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, RefusalPayload, Tool, ToolContext, ToolExample, ToolName, ToolOutput, ToolProfile, ToolResult, ToolSpec}

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
  override val name = ToolName("delegate_task")
  override val description =
    """Spawn a worker agent for long-running or specialized work. The worker runs as a real agent in
      |its own sub-conversation linked to this one; you stay in that sub-conversation as its supervisor
      |(its "user") — you task it, answer its questions, and decide what to surface back here. Requires
      |`role` (a short role name like "researcher") and `brief` (the directive). `roleDescription`
      |optionally overrides the worker's identity. `modelId` is optional (omit to let the framework
      |route, falling back to your own model). `toolNames` is the worker's work roster (on top of
      |the framework reply + capability-discovery essentials it always has). Returns the worker's id +
      |sub-conversation id.
      |Use for "research X", "build Y", "analyze Z" — anything you'd rather hand off than answer inline.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("delegate", "worker", "spawn", "task", "research", "background", "subagent"))
  )
  override val examples = List(
    ToolExample(
      "Delegate a research task",
      DelegateTaskInput(
        role = "researcher",
        roleDescription = Some("You are a research agent. Find relevant sources, synthesize, and report."),
        brief = "Find recent papers on retrieval-augmented generation in 2026.",
        goal = Some("identify candidate sources for a literature review")
      )
    )
  )

  override def executeResult(input: DelegateTaskInput, ctx: ToolContext): Task[ToolResult[DelegateTaskOutput]] =
    // Sigil #348 — structural depth cap. The doer framing on the worker
    // (WorkerSelfSkill) is the primary fix for re-delegation; this is the
    // bound that keeps delegation safe even when that framing doesn't hold
    // on a weak model. Refuse before spawning if the new worker would
    // exceed maxDelegationDepth (top-level → worker = depth 1, → sub-worker
    // = depth 2, deeper refused). Breadth (fan-out) at the allowed depth is
    // still fine — only chain depth is capped.
    ctx.sigil.delegationDepth(ctx.conversation.id).flatMap { callerDepth =>
      val cap = ctx.sigil.maxDelegationDepth
      if (callerDepth + 1 > cap)
        Task.pure(ToolResult.failure(
          message = s"delegate_task refused: delegation depth cap reached (max $cap). This conversation is " +
            s"already $callerDepth level(s) deep in a worker chain; spawning another worker would exceed the cap.",
          hint = Some("You are a delegated worker — do this brief YOURSELF rather than re-delegating it. " +
            "Delegation depth is capped to prevent runaway worker→worker recursion (sigil #348).")
        ))
      else dispatchValidated(input, ctx)
    }

  private def dispatchValidated(input: DelegateTaskInput, ctx: ToolContext): Task[ToolResult[DelegateTaskOutput]] = {
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
    // Build the worker's Role from the flat input (sigil #346) — the
    // brief doubles as the identity statement when no override is given.
    val role         = sigil.role.Role(name = input.role, description = input.roleDescription.getOrElse(input.brief))
    val workerLabel  = s"Worker: ${role.name}"

    val resolvedModelTask: Task[LId[Model]] = input.modelId match {
      case Some(explicit) =>
        Task.pure(host.cache.findTolerant(LId[Model](explicit.toLowerCase)).map(_._id).getOrElse(LId[Model](explicit)))
      case None =>
        host.routedModelFor(
          workType   = role.workType,
          chain      = ctx.chain,
          fallback   = ctx.modelId,
          complexity = input.complexity
        )
    }

    val workerId   = WorkerParticipantId(s"${role.name}-${rapid.Unique()}")
    val workerTools = input.toolNames.map(ToolName.internal)
    val brief       = composeBrief(input)
    // #355 — the worker inherits the spawning conversation's mode by default
    // (a coding supervisor yields a coding worker, with its skill/roster), and
    // an explicit `mode` name overrides. An unknown/blank name falls back to
    // the inherited mode rather than the generic ConversationMode default.
    val workerMode = input.mode.map(_.trim).filter(_.nonEmpty)
      .flatMap(host.modeByName)
      .getOrElse(ctx.conversation.currentMode)

    for {
      resolvedModel <- resolvedModelTask
      workerAgent = DefaultAgentParticipant(
        id        = workerId,
        modelId   = resolvedModel,
        toolNames = workerTools,
        tools     = ToolPolicy.Standard,
        workType  = role.workType,
        roles     = List(role)
      )
      // The sub-conversation: supervisor (the delegating agent) + worker,
      // linked to the parent so the worker inherits its workspace (#325)
      // and the supervisor can relay between the two.
      // #351 — do NOT pin complexity here (reverses #335). Delegation
      // happens at the moment of LEAST information about the task: the
      // brief is set before any grep/read/discovery, so an early guess
      // can't yet know Low vs VeryHigh. Pinning freezes it for the
      // worker's whole life, blocking the per-turn classifier from
      // adapting as understanding accrues. `input.complexity` still seeds
      // the worker's INITIAL model routing above (`routedModelFor`), but
      // each turn re-classifies thereafter; `pinnedComplexity` stays the
      // home for `request_escalation`'s earned, sticky bump only.
      workerConv <- host.newConversation(
        createdBy            = ctx.caller,
        label                = workerLabel,
        summary              = input.goal.getOrElse(brief).take(80),
        participants         = List(supervisor, workerAgent),
        currentMode          = workerMode,
        parentConversationId = Some(parentConvId)
      )
      // Activate the supervisor bridge guidance on the caller's projection
      // in the worker conversation (renders only while it acts there).
      _ <- host.activateSkill(
        conversationId = workerConv._id,
        participantId  = ctx.caller,
        source         = SkillSource.Supervisor,
        slot           = WorkerSupervisorSkill.slot(brief, parentConvId, role.name)
      )
      // Sigil #348 — symmetric doer framing on the WORKER's own projection:
      // it is the delegated agent for this brief, must carry it out itself
      // and report back, and must not re-delegate its whole assignment.
      _ <- host.activateSkill(
        conversationId = workerConv._id,
        participantId  = workerId,
        source         = SkillSource.Worker,
        slot           = WorkerSelfSkill.slot(brief, role.name)
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
      role         = role.name
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
