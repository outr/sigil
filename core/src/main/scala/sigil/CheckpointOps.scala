package sigil

import fabric.rw.*
import lightdb.id.Id
import lightdb.lucene.LuceneStore
import lightdb.postgresql.PostgreSQLStoreManager
import lightdb.rocksdb.RocksDBSharedStore
import lightdb.sql.connect.{HikariConnectionManager, SQLConfig}
import lightdb.store.CollectionManager
import lightdb.store.split.SplitStoreManager
import lightdb.time.Timestamp
import lightdb.util.Nowish
import profig.Profig
import rapid.{Stream, Task, logger}
import sigil.conversation.{ActiveSkillSlot, ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, EncodedContext, FrameBuilder, MemorySource, MemoryStatus, ParticipantProjection, ProgressContext, SkillSource, ToolCallState, Topic, TopicEntry, TopicShiftResult, TurnInput, TurnPlan, UpsertMemoryResult}
import sigil.SpaceId
import sigil.cache.ModelRegistry
import sigil.controller.OpenRouter
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.governor.{BudgetDirective, BudgetGovernor, CheckpointIntervention, GovernorContext}
import sigil.governor.{DegenerateGenerationGovernor, GovernorVote, OutcomeGovernor, PlainTextReplyGovernor,
  ProgressGovernor, TurnDecisionGovernor, TurnGovernor}
import sigil.transport.SignalTransport

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sigil.tool.consult.{ConsultTool, TopicClassifierTool}
import sigil.provider.{GenerationSettings, TokenUsage}
import sigil.db.{DefaultSigilDB, Model, SigilDB}
import sigil.dispatcher.{StopFlag, TriggerFilter}
import sigil.event.{AgentState, CapabilityResults, Event, EventsPage, Message, MessageRole, MessageVisibility, ModeChange, Stop, ToolInvoke, TopicChange, TopicChangeKind}
import sigil.role.Role
import sigil.orchestrator.{BudgetScope, Directive, Orchestrator}
import sigil.provider.{Complexity, ConversationMode, ConversationRequest, Mode, ProviderStrategy, ReasoningMode, ToolPolicy, WorkType}
import sigil.information.Information
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.pipeline.{ContentExternalizationTransform, GeocodingEnrichmentEffect, InboundTransform, LocationCaptureTransform, MemoryCacheInvalidationEffect, MessageIndexingEffect, RedactLocationTransform, RespondOptionsSelectionFramingTransform, SettledEffect, SignalHub, TopicIndexCanonicalizingTransform, ViewerTransform, WorkerConversationAddressingTransform}
import sigil.render.{ContentRenderer, HtmlRenderer, MarkdownRenderer, PlainTextRenderer, SlackMrkdwnRenderer}
import sigil.provider.Provider
import sigil.provider.{ContextSection, ContextSections, InstructionTier, ModelProfile, PromptShape, Reliability, ResolvedReferences}
import sigil.service.Service
import sigil.signal.{AgentActivity, AgentStateDelta, CoreSignals, Delta, EventState, LocationDelta, Notice, ServiceLogSignal, ServiceStatusSignal, Signal, ToolDelta, TopicDelta}
import sigil.spatial.{Geocoder, NoOpGeocoder, Place}
import sigil.tool.Tool
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext}
import sigil.tool.core.{CoreTools, FindCapabilityTool}
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolFinder, ToolInput}
import sigil.vector.{NoOpVectorIndex, VectorIndex, VectorPoint, VectorPointId, VectorSearchResult}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Progress- and planner-checkpoint cluster — the sparse mid-turn
 * oversight the agent loop runs at iteration boundaries. Owns the
 * per-claim [[CheckpointState]] latch, the executor-tier progress
 * reflection, the planner-tier verdict path, the stall / hard-stall
 * evaluators over the conversation tail, and the prompt renderers
 * both tiers share.
 *
 * Mixed into [[Sigil]]; the self-type reaches the config knobs
 * (`plannerModelId`, `consecutiveNoProgressLimit`, …), `withDB`,
 * `publish`, and the consult surface the checkpoints call through.
 */
trait CheckpointOps { this: Sigil =>

  /** Per-claim progress-checkpoint state. Keyed by the AgentState id
    * that owns the claim. Carries the prior checkpoint's `currentStatus`
    * (anchor for the next checkpoint's "did things change?" question),
    * the count of consecutive `meaningfulProgress = false`
    * checkpoints — the framework intervenes when the count reaches
    * [[consecutiveNoProgressLimit]] — and the churn chain: the prior
    * window's mutation targets plus how many consecutive windows have
    * re-mutated only already-seen targets without any verification
    * call. Populated on first checkpoint; cleared on `releaseClaim`.
    *
    * When the planner tier is enabled ([[plannerModelId]]) the state
    * additionally carries the turn's plan artifact, the iteration of
    * the most recent planner review, and the anomaly latch that
    * mechanical signals (stall heuristics, churn chain, budget
    * check-in) set to arm the next boundary's planner consult. */
  private[sigil] final case class CheckpointState(@volatile var lastStatus: Option[String],
                                            @volatile var noProgressStreak: Int,
                                            @volatile var lastMutationTargets: Set[String] = Set.empty,
                                            @volatile var repeatUnverifiedWindows: Int = 0,
                                            @volatile var plan: Option[TurnPlan] = None,
                                            @volatile var lastPlannerIteration: Int = 0,
                                            @volatile var plannerAnomalyPending: Boolean = false)
  private[sigil] final val checkpointStates: ConcurrentHashMap[Id[Event], CheckpointState] = new ConcurrentHashMap()

  /** Run the checkpoint for a boundary the caller has already decided is
    * due. `modelProfile` and `plannerCadence` come from the
    * [[sigil.governor.GovernorContext]] assembled once per boundary, so
    * the per-model derivations are not repeated here. */
  private[sigil] final def runProgressCheckpoint(agent: AgentParticipant,
                                                 convId: Id[Conversation],
                                                 claimed: AgentState,
                                                 iteration: Int,
                                                 modelProfile: ModelProfile,
                                                 plannerCadence: Int): Task[Option[CheckpointIntervention]] = Task.defer {
    if (plannerModelId.isDefined)
      runPlannerCheckpoint(agent, convId, claimed, iteration, plannerModelId.get, modelProfile, plannerCadence)
    else {
      val state = checkpointStates.computeIfAbsent(claimed._id,
        _ => CheckpointState(lastStatus = None, noProgressStreak = 0))
      val priorStatus = state.lastStatus
      val stallTask = evaluateStall(convId, agent.id)
      loadProgressContext(convId, agent.id).flatMap { ctx =>
        val systemPrompt =
          """You are reflecting on the agent's progress on a specific user task. Given the
            |user's request, the tool activity in the window since the prior checkpoint, and
            |the prior checkpoint status, assess whether THIS WINDOW moved the task forward.
            |Be honest: if nothing changed since the prior status, set
            |meaningfulProgress = false so the framework can intervene — but describe the
            |window in your own words; never copy the prior status verbatim.
            |
            |What counts as meaningful progress (Sigil #385 — do NOT inflate it):
            |  - NEW information that materially advances toward the deliverable, or a
            |    concrete action that produces/changes an artifact the task asked for
            |    (an edit, a save, a send, a created file, a final answer to the user).
            |  - Reading, viewing, examining, listing, searching, or "gathering context"
            |    is NOT progress by itself — when the task is to DO something, repeatedly
            |    inspecting files/images while producing nothing is a stall, however many
            |    distinct things were inspected. If the window is all reads/views and no
            |    deliverable, set meaningfulProgress = false.
            |  - A respond marked "mid-task status update" is NOT a final answer — continued
            |    tool calls after it are normal, not a contradiction. Never report the task
            |    complete because a status update went out; the task is complete when the
            |    WORK is done.
            |  - Edits alone are not self-evidently progress: re-editing the same file
            |    window after window with no compile/test/diagnostics call in between is
            |    churn — nothing in the loop can learn whether anything was fixed. If your
            |    summary would repeat "applied N edits… continuing" again, that repetition
            |    is itself evidence of churn, not progress.
            |  - A status of "acknowledged / summarized / ready / awaiting next instruction"
            |    while the agent is STILL calling tools is a contradiction, not completion:
            |    set meaningfulProgress = false and shouldAskUser = false. The agent isn't
            |    done — it's looping. Only set shouldAskUser = true when the task genuinely
            |    cannot proceed without a decision only the user can make.""".stripMargin
        val userPrompt = renderCheckpointPrompt(ctx, priorStatus, iteration)
        // #357 — the reflection normally judges on the agent's own model
        // (#320/#321). When `pinCoversAuxiliaryCalls` is set and the
        // conversation is pinned, the pin wins; otherwise the default
        // path is untouched (no conversation read).
        // Sigil #394 — route the checkpoint through the SAME consult path as
        // every other FrameworkConsult (`auxModelFor` → `routedModelFor` by
        // `consultWorkType`), so an app's `WorkType` routes (and the pin, via
        // `pinCoversAuxiliaryCalls`) cover it. Previously it pinned to
        // `agent.modelId`, so a `ClassificationWork -> Haiku` route never
        // reached the checkpoint and it ran on the agent's default model (e.g.
        // Fable, which can't satisfy the forced report → silent None).
        val resolveCheckpointModel: Task[Id[Model]] =
          auxModelFor(convId, sigil.tool.consult.ProgressReflectionTool.consultWorkType,
            List(agent.id), progressReflectionModelFor(agent))
        // Sigil #394 — shared processing for a checkpoint report: persist the
        // ProgressCheckpoint, update the no-progress streak, and build the
        // cooperative/terminal intervention. Called both for a real reflector
        // report AND (fail-safe) for a stall-synthesized report when the
        // reflector returned None but the objective StallDetector saw a stall.
        def processCheckpointReport(report: sigil.tool.consult.ProgressReflectionInput,
                                    rawStall: sigil.conversation.compression.StallDetector.Signal): Task[Option[CheckpointIntervention]] =
          withDB(_.conversations.transaction(_.get(convId))).flatMap { convOpt =>
            val topicId = convOpt.flatMap(_.topics.lastOption.map(_.id))
              .getOrElse(_root_.sigil.conversation.Topic.id("__no_topic__"))
            // Same-target churn: a window whose mutations touch only
            // already-seen targets with no verification call is not
            // iteration — nothing in it could learn whether anything
            // was fixed. One such window is ambiguous (the verify may
            // be queued); two consecutive make it churn, an objective
            // stall signal that overrides both the mutation veto AND
            // the reflector's own blessing (the observed loop had the
            // reflector approving "successfully applied edits" while
            // the same lines were re-edited nine times). New targets
            // or a verification call reset the chain, so fix →
            // compile → fix-again and bulk many-file sweeps are
            // untouched. The per-window StallDetector tail cannot see
            // this pattern — it resets at every checkpoint.
            val targets = ctx.windowMutationTargets
            val churnWindow = targets.nonEmpty && !ctx.windowVerified &&
              targets.subsetOf(state.lastMutationTargets)
            state.repeatUnverifiedWindows = if (churnWindow) state.repeatUnverifiedWindows + 1 else 0
            state.lastMutationTargets = targets
            val stall =
              if (rawStall.detected) rawStall
              else if (state.repeatUnverifiedWindows >= 2)
                sigil.conversation.compression.StallDetector.Signal(
                  detected = true,
                  reason   = Some(
                    s"You have re-edited the same target(s) (${targets.toList.sorted.take(3).mkString(", ")}) across " +
                      s"${state.repeatUnverifiedWindows + 1} checkpoint windows without any compile/test/diagnostics call — " +
                      "editing again cannot tell you whether anything is fixed. VERIFY the current state (compile, run " +
                      "diagnostics) before touching those targets again."))
              else sigil.conversation.compression.StallDetector.Signal.Empty
            // Sigil bug #124 — fold the objective stall signal into the
            // reflector's self-assessment. The agent's `meaningfulProgress`
            // self-report is necessary but not sufficient; if the
            // StallDetector spots an identical-call streak or empty-
            // payload streak, the persisted checkpoint records
            // `meaningfulProgress = false` regardless of what the agent
            // said, and `stuckOn` carries the detector's reason so the
            // intervention message names the loop concretely.
            //
            // The mechanical evidence cuts the other way too: settled
            // successful invocations of destructive-annotated tools in
            // this window are objective proof that external state
            // changed — a turn actively applying edits must never
            // accumulate a no-progress streak on the strength of the
            // reflector's status text alone. The StallDetector keeps
            // veto authority over both signals (an identical-args write
            // loop is still a stall, however many "successes" it
            // settles).
            val effectiveMeaningful =
              (report.meaningfulProgress || ctx.windowMutations > 0) && !stall.detected
            val effectiveStuckOn    = stall.reason.orElse(report.stuckOn)
            val checkpoint = sigil.event.ProgressCheckpoint(
              participantId        = agent.id,
              conversationId       = convId,
              topicId              = topicId,
              iterationCount       = iteration,
              prevCheckpointStatus = priorStatus,
              currentStatus        = report.currentStatus,
              meaningfulProgress   = effectiveMeaningful,
              remainingSteps       = report.remainingSteps,
              stuckOn              = effectiveStuckOn,
              shouldAskUser        = report.shouldAskUser
            )
            publish(checkpoint).flatMap { _ =>
              // Update side-state for the next checkpoint comparison.
              state.lastStatus = Some(report.currentStatus)
              if (!effectiveMeaningful) {
                state.noProgressStreak = state.noProgressStreak + 1
              } else {
                state.noProgressStreak = 0
              }
              val stuck = state.noProgressStreak >= consecutiveNoProgressLimit
              if (report.shouldAskUser || stuck || stall.detected) {
                val directive: Directive =
                  if (report.shouldAskUser)
                    // Genuine ask-the-user terminal — the agent asks the
                    // human itself. In a directed worker the governor
                    // substitutes the supervisor handoff.
                    Directive.StallAskUser
                  else if (stall.detected)
                    // Stall-detector hit on the current checkpoint —
                    // intervene immediately rather than waiting for
                    // `consecutiveNoProgressLimit` streaks to stack.
                    Directive.ProgressCheckpoint(
                      stall.reason.getOrElse(
                        "You have repeated the same kind of call without gaining new information."),
                      effectiveStuckOn)
                  else
                    Directive.ProgressCheckpoint(
                      s"You have run $iteration iterations without meaningful progress since: " +
                        s"\"${priorStatus.getOrElse(report.currentStatus)}\".",
                      effectiveStuckOn)
                // Bug #133 — distinguish "ask the user" (genuine
                // terminal — needs human input) from "agent should
                // act differently now" (directive — agent gets one
                // more iteration). The governor routes each to the
                // right shape and publishes the typed directive on the
                // internal Tool-role channel.
                Task.pure(Some(CheckpointIntervention(
                  directive = directive,
                  askingUser = report.shouldAskUser,
                  // Sigil #385 — escalate to a TERMINAL forced synthesis once
                  // the no-progress streak has persisted past
                  // `hardNoProgressLimit`. A cooperative nudge gets the agent
                  // a few chances to change approach; after that, a varied-
                  // but-unproductive loop (which evades the identical-call
                  // hard-stall) must be stopped, not nudged again.
                  terminal = terminalOnPersistentNoProgress(state.noProgressStreak)
                )))
              } else Task.pure(None)
            }
          }
        resolveCheckpointModel.flatMap { checkpointModelId =>
        sigil.tool.consult.ConsultTool.invoke[sigil.tool.consult.ProgressReflectionInput](
          sigil = this,
          modelId = checkpointModelId,
          chain = List(agent.id),
          systemPrompt = systemPrompt,
          userPrompt = userPrompt,
          tool = sigil.tool.consult.ProgressReflectionTool,
          generationSettings = sigil.tool.consult.ProgressReflectionTool.consultSettings
        ).flatMap {
        case Some(report) => stallTask.flatMap(stall => processCheckpointReport(report, stall))
        case None         =>
          // Sigil #394 — fail SAFE, not open. A checkpoint that couldn't
          // produce a report (a forced-tool_choice-incompatible model returned
          // naked text → None) must not silently switch OFF stall detection
          // (observed: 44 of 64 checkpoints returned None on Fable, the streak
          // never built, zero interventions across 84 iterations). Fall back to
          // the objective signals — the StallDetector verdict and the
          // same-target churn chain — so an incapable checkpoint model
          // can't disable the guardrails entirely.
          stallTask.flatMap { stall =>
            val wouldChurn = ctx.windowMutationTargets.nonEmpty && !ctx.windowVerified &&
              ctx.windowMutationTargets.subsetOf(state.lastMutationTargets) &&
              state.repeatUnverifiedWindows + 1 >= 2
            if (stall.detected || wouldChurn)
              processCheckpointReport(
                sigil.tool.consult.ProgressReflectionInput(
                  currentStatus      = stall.reason.getOrElse("Repeating the same kind of action without new information."),
                  meaningfulProgress = false,
                  remainingSteps     = "",
                  stuckOn            = stall.reason,
                  shouldAskUser      = false),
                stall)
            else Task.pure(None)
          }
      }.handleError { e =>
        Task(scribe.warn(s"runProgressCheckpoint failed for ${agent.id.value}/${convId.value} iter=$iteration: ${e.getMessage}"))
          .map(_ => None)
      }
      }
      }
    }
  }

  /** Planner-tier progress checkpoint ([[plannerModelId]]). Replaces
    * the executor's self-assessment with a higher-tier verdict against
    * an explicit [[TurnPlan]]. The mechanical signals (StallDetector,
    * same-target churn chain) arm the planner via the anomaly latch
    * instead of driving the self-report streak machinery, and the
    * planner LLM call itself fires sparsely: on a pending anomaly,
    * when no plan exists yet (the first review creates it), or on the
    * [[plannerCadence]] tick. All other boundaries are free. */
  private final def runPlannerCheckpoint(agent: AgentParticipant,
                                         convId: Id[Conversation],
                                         claimed: AgentState,
                                         iteration: Int,
                                         plannerModel: Id[Model],
                                         modelProfile: ModelProfile,
                                         plannerCadence: Int): Task[Option[CheckpointIntervention]] = {
    val state = checkpointStates.computeIfAbsent(claimed._id,
      _ => CheckpointState(lastStatus = None, noProgressStreak = 0))
    evaluateStall(convId, agent.id).flatMap { rawStall =>
      loadProgressContext(convId, agent.id).flatMap { ctx =>
        // Fold this window into the same-target churn chain — the same
        // fold the reflector path applies in processCheckpointReport.
        val targets = ctx.windowMutationTargets
        val churnWindow = targets.nonEmpty && !ctx.windowVerified && targets.subsetOf(state.lastMutationTargets)
        state.repeatUnverifiedWindows = if (churnWindow) state.repeatUnverifiedWindows + 1 else 0
        state.lastMutationTargets = targets
        val churnReason =
          if (state.repeatUnverifiedWindows >= 2)
            Some(s"The same target(s) (${targets.toList.sorted.take(3).mkString(", ")}) have been re-mutated across " +
              s"${state.repeatUnverifiedWindows + 1} checkpoint windows without any compile/test/diagnostics call.")
          else None
        val stallReason =
          if (rawStall.detected)
            rawStall.reason.orElse(Some("Repeating the same kind of call without gaining new information."))
          else None
        val anomalyReason = stallReason.orElse(churnReason)
        anomalyReason.foreach(_ => state.plannerAnomalyPending = true)
        val cadenceDue = plannerCadence > 0 && iteration - state.lastPlannerIteration >= plannerCadence
        // An executor declared as needing oversight treats every cadence
        // tick as armed: the review runs with full anomaly framing rather
        // than the sparse, anomaly-only path a frontier executor gets.
        if (cadenceDue && modelProfile.needsOversight)
          state.plannerAnomalyPending = true
        if (!state.plannerAnomalyPending && state.plan.isDefined && !cadenceDue) Task.pure(None)
        else withDB(_.conversations.transaction(_.get(convId))).flatMap {
          case None => Task.pure(None)
          case Some(conv) =>
            val turnCost = Option(activeClaims.get(claimed._id)).map(_.turnCost)
            val promptAnomaly = anomalyReason.orElse {
              if (state.plannerAnomalyPending)
                Some("An anomaly signal (stall heuristic, churn chain, or budget check-in) fired since the last planner review.")
              else None
            }
            sigil.tool.consult.ConsultTool.invoke[sigil.tool.consult.PlannerVerdictInput](
              sigil = this,
              modelId = plannerModel,
              chain = List(agent.id),
              systemPrompt = plannerSystemPrompt,
              userPrompt = renderPlannerPrompt(ctx, state.plan, iteration, turnCost, promptAnomaly),
              tool = sigil.tool.consult.PlannerVerdictTool,
              generationSettings = sigil.tool.consult.PlannerVerdictTool.consultSettings
            ).flatMap {
              case Some(verdict) => applyPlannerVerdict(agent, conv, state, iteration, verdict)
              case None =>
                // No verdict from the planner — fall back to the
                // objective signals rather than failing open: a stall /
                // churn hit still persists a no-progress checkpoint and
                // produces the cooperative intervention.
                anomalyReason match {
                  case Some(reason) =>
                    val checkpoint = sigil.event.ProgressCheckpoint(
                      participantId        = agent.id,
                      conversationId       = convId,
                      topicId              = conv.currentTopicId,
                      iterationCount       = iteration,
                      prevCheckpointStatus = state.lastStatus,
                      currentStatus        = reason,
                      meaningfulProgress   = false,
                      remainingSteps       = state.plan.map(_.doneCriteria).getOrElse(""),
                      stuckOn              = Some(reason),
                      shouldAskUser        = false
                    )
                    publish(checkpoint).map { _ =>
                      state.lastStatus = Some(reason)
                      state.noProgressStreak = state.noProgressStreak + 1
                      Some(CheckpointIntervention(
                        directive  = Directive.ProgressCheckpoint(reason, Some(reason)),
                        askingUser = false,
                        terminal   = terminalOnPersistentNoProgress(state.noProgressStreak)
                      ))
                    }
                  case None => Task.pure(None)
                }
            }.handleError { e =>
              Task(scribe.warn(s"runPlannerCheckpoint failed for ${agent.id.value}/${convId.value} iter=$iteration: ${e.getMessage}"))
                .map(_ => None)
            }
        }
      }
    }
  }

  /** Route a planner verdict: maintain the plan artifact (created on
    * the first review, revised on replan), publish the `_plan` and
    * `_planner_correction` directives, and persist the checkpoint.
    * Always non-terminal — a plan-holding model saying on_track must
    * never be stall-killed, and a deviating correction gives the
    * executor at least one iteration to act on it. */
  private final def applyPlannerVerdict(agent: AgentParticipant,
                                        conv: Conversation,
                                        state: CheckpointState,
                                        iteration: Int,
                                        verdict: sigil.tool.consult.PlannerVerdictInput): Task[Option[CheckpointIntervention]] = {
    state.lastPlannerIteration = iteration
    state.plannerAnomalyPending = false
    val returnedPhase = Some(verdict.currentPhase.trim).filter(_.nonEmpty)
    val returnedPlan =
      if (verdict.objective.trim.nonEmpty && verdict.doneCriteria.trim.nonEmpty)
        Some(TurnPlan(
          objective    = verdict.objective.trim,
          constraints  = verdict.constraints.map(_.trim).filter(_.nonEmpty),
          doneCriteria = verdict.doneCriteria.trim,
          currentPhase = returnedPhase))
      else None
    val replan = verdict.verdict == "replan"
    val publishPlanTask = returnedPlan match {
      case Some(plan) if replan || state.plan.isEmpty =>
        state.plan = Some(plan)
        publishInternalDirective(agent, conv, Directive.Plan(plan))
      case _ =>
        state.plan = state.plan.map(p => p.copy(currentPhase = returnedPhase.orElse(p.currentPhase)))
        Task.unit
    }
    val deviating = verdict.verdict == "deviating"
    val correction = Some(verdict.correction.trim).filter(_.nonEmpty)
      .getOrElse("Re-read the plan and realign your next actions with its objective and done criteria.")
    val correctionTask =
      if (deviating)
        publishInternalDirective(agent, conv, Directive.PlannerCorrection(correction))
      else Task.unit
    val status =
      if (deviating) s"Planner: deviating — $correction"
      else if (replan) s"Planner: replanned — ${returnedPhase.getOrElse("plan revised")}"
      else s"Planner: on track — ${returnedPhase.getOrElse("progressing")}"
    val checkpoint = sigil.event.ProgressCheckpoint(
      participantId        = agent.id,
      conversationId       = conv._id,
      topicId              = conv.currentTopicId,
      iterationCount       = iteration,
      prevCheckpointStatus = state.lastStatus,
      currentStatus        = status,
      meaningfulProgress   = !deviating,
      remainingSteps       = state.plan.map(_.doneCriteria).getOrElse(""),
      stuckOn              = if (deviating) Some(correction) else None,
      shouldAskUser        = false
    )
    publishPlanTask
      .flatMap(_ => correctionTask)
      .flatMap(_ => publish(checkpoint))
      .map { _ =>
        state.lastStatus = Some(status)
        state.noProgressStreak = if (deviating) state.noProgressStreak + 1 else 0
        None
      }
  }

  private val plannerSystemPrompt: String =
    """You are the planning tier overseeing an executor agent's work on a user task. You hold
      |the plan; the executor does the steps. Judge STRATEGY, not steps: is the window's work
      |converging on the plan's done criteria? Is the executor undoing or redoing its own
      |work? Is effort being spent outside the objective? Do not judge whether individual
      |tool calls succeeded — judge whether the trajectory still leads to the objective.
      |
      |Verdicts:
      |  - on_track — the work is converging on the done criteria. Echo the current plan
      |    fields, refreshing currentPhase to where the work stands now. correction stays empty.
      |  - deviating — the executor has lost the plot: undoing its own work, grinding on
      |    something outside the objective, or repeating work that cannot converge. Write a
      |    concrete correction directive telling it what to do differently. Echo the plan.
      |  - replan — the plan itself no longer fits what the task needs. Return the REVISED
      |    objective / constraints / doneCriteria / currentPhase. correction stays empty.
      |
      |On the first review there is no plan yet: derive one from the user's request and the
      |work so far — objective (what the task delivers), constraints (hard boundaries the
      |executor must not cross), doneCriteria (how anyone can tell the work is finished) —
      |and return it with your verdict.""".stripMargin

  /** Render the `_plan` directive the executor reads: the plan
    * artifact as an internal Tool-role message. */
  private def renderPlanDirective(plan: TurnPlan): String = Directive.Plan(plan).render

  /** Stitch the planner consult's user prompt: the user task, the held
    * plan, the window's activity, turn spend, and the anomaly reason
    * when one armed this review. Window-scoped and line-bounded via
    * [[loadProgressContext]], so the prompt stays bounded regardless
    * of conversation length. */
  private def renderPlannerPrompt(ctx: ProgressContext,
                                  plan: Option[TurnPlan],
                                  iteration: Int,
                                  turnCost: Option[BigDecimal],
                                  anomaly: Option[String]): String = {
    val taskBlock = ctx.userTask match {
      case Some(t) =>
        val directiveLine = ctx.latestDirective match {
          case Some(d) => s"The user has since said \"$d\" to continue this objective.\n\n"
          case None    => "\n"
        }
        s"The user's request:\n\"$t\"\n\n" + directiveLine
      case None => "The user's request: (no recent substantive user message found)\n\n"
    }
    val planBlock = plan match {
      case Some(p) =>
        val constraints = if (p.constraints.isEmpty) "(none)" else p.constraints.mkString("; ")
        s"The plan you hold:\n  Objective: ${p.objective}\n  Constraints: $constraints\n" +
          s"  Done when: ${p.doneCriteria}\n  Current phase: ${p.currentPhase.getOrElse("(not set)")}\n\n"
      case None => "The plan you hold: (none yet — this is your first review; derive one and return it)\n\n"
    }
    val earlierLine =
      if (ctx.earlierCalls > 0)
        s"(${ctx.earlierCalls} earlier calls preceded this window — judge the trajectory on the window below.)\n"
      else ""
    val historyBlock = ctx.toolHistory match {
      case Nil => s"${earlierLine}The executor's work since the last checkpoint: (no tool calls this window)\n\n"
      case list =>
        val numbered = list.zipWithIndex.map { case (line, i) => s"  ${i + 1}. $line" }.mkString("\n")
        s"${earlierLine}The executor's work since the last checkpoint:\n$numbered\n\n"
    }
    val spendLine = turnCost.filter(_ > 0).map(c => f"This turn has spent $$${c}%.2f so far.\n\n").getOrElse("")
    val anomalyLine = anomaly.map(a => s"An anomaly signal fired: $a\n\n").getOrElse("")
    val ask =
      s"The executor is at iteration $iteration. Deliver your verdict: on_track when this trajectory is " +
        "converging on the done criteria, deviating (with a concrete correction) when it is not, replan when " +
        "the plan itself no longer fits. Populate the plan fields — fully on first review or replan, echoed " +
        "with a refreshed currentPhase otherwise."
    taskBlock + planBlock + historyBlock + spendLine + anomalyLine + ask
  }

  /** Load the context the reflection prompt needs: the user's most
    * recent substantive Message + the agent's tool-call history in the
    * window since the prior checkpoint. Best-effort — failures fall
    * through to empty context rather than aborting the checkpoint.
    *
    * The window scoping matters more than it looks: the history was
    * previously the whole arc since the objective, head-capped — so
    * past ~20 calls the reflector's input FROZE, its status echoed
    * verbatim forever, and the "identical status = no progress" rule
    * marched every long healthy turn into a forced kill. */
  protected final def loadProgressContext(convId: Id[Conversation],
                                          agentId: ParticipantId): Task[ProgressContext] =
    withDB { db =>
      db.conversationEvents(convId).flatMap { all =>
        val convEvents = all.iterator
          .collect { case e: Event if e.conversationId == convId => e }
          .toList
          .sortBy(_.timestamp.value)
        // #320 — the objective is the most-recent substantive (non-
        // continuation) user message, NOT the latest turn. A bare
        // "Proceed" advances the task; it isn't the task. Render the
        // continuation alongside so the reflector sees what was just
        // asked while judging progress against the real goal.
        val userMsgs = convEvents.collect {
          case m: Message
            if !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId] &&
               m.role == MessageRole.Standard &&
               m.content.nonEmpty =>
            m
        }
        val (substantiveUser, directive) =
          sigil.conversation.ProgressTaskSelector.select(userMsgs, m => textOfContent(m.content))
        // #330 (defense in depth, same family as #320) — re-anchor on the
        // active objective when there's no substantive user message to judge
        // against (agent-initiated turns, and any non-user-driven loop). Use
        // the earliest substantive Standard message in the conversation as
        // the objective rather than treating "no user message" as evidence
        // of idle cycling and asking for clarification.
        val substantive = substantiveUser.orElse(
          convEvents.collect {
            case m: Message if m.role == MessageRole.Standard && m.content.nonEmpty => m
          }.minByOption(_.timestamp.value)
        )
        val task: Option[String]            = substantive.map(m => textOfContent(m.content))
        val latestDirective: Option[String] = directive.map(m => textOfContent(m.content))
        val objectiveCutoff = substantive.map(_.timestamp.value).getOrElse(0L)
        // The window opens at the prior checkpoint — never before the
        // objective (a checkpoint left over from an earlier task must
        // not stretch the window backwards).
        val windowCutoff =
          math.max(objectiveCutoff, priorCheckpointCutoff(convEvents, agentId).getOrElse(0L))
        // Sigil #265 — each tool transaction lives on a single stateful
        // ToolInvoke; the post-settle outcome is on the invoke itself,
        // so per-invoke rendering reads directly off the row. Internal
        // framework diagnostics (`_stall_detected`, challenge invokes)
        // are bookkeeping, not agent activity — excluded.
        val arcInvokes = convEvents.collect {
          case ti: sigil.event.ToolInvoke
            if ti.timestamp.value > objectiveCutoff && ti.participantId == agentId && !ti.internal => ti
        }.sortBy(_.timestamp.value)
        val windowInvokes = arcInvokes.filter(_.timestamp.value > windowCutoff)
        val shown = windowInvokes.takeRight(20)
        // Lenient read — a de-registered tool's stale row must not
        // abort checkpoint progress evaluation mid-turn.
        db.tools.transaction(_.jsonStream.toList).map(Sigil.decodeToolsLeniently).map { toolRows =>
          val toolsByName = toolRows.iterator.map(t => t.name.value -> t).toMap
          // Respond-family pulses publish a Message — user-visible
          // delivery, not external work. Counting them as mutations
          // would let a status-pulse-per-window loop veto stalls
          // forever.
          def isMutation(ti: sigil.event.ToolInvoke): Boolean =
            ti.outcome == sigil.event.ToolOutcome.Success &&
              !_root_.sigil.tool.core.RespondFamilyTool.contains(ti.toolName) &&
              toolsByName.get(ti.toolName.value).exists(_.spec.profile.effect.mutates)
          val successfulMutations = windowInvokes.filter(isMutation)
          val targets = successfulMutations.flatMap { ti =>
            toolsByName.get(ti.toolName.value).flatMap(t => ti.input.flatMap(t.mutationTargetOf)).map(_.value)
          }.toSet
          val verified = windowInvokes.exists(ti =>
            ti.outcome == sigil.event.ToolOutcome.Success &&
              toolsByName.get(ti.toolName.value).exists(_.verification))
          ProgressContext(
            userTask              = task,
            toolHistory           = shown.map(renderInvokeHistoryLine),
            latestDirective       = latestDirective,
            earlierCalls          = arcInvokes.size - shown.size,
            windowMutations       = successfulMutations.size,
            windowMutationTargets = targets,
            windowVerified        = verified
          )
        }
      }
    }.handleError(_ => Task.pure(ProgressContext(None, Nil)))

  /** One reflection-history line for a window invoke. Respond calls
    * carry their `endsTurn` framing: a mid-task status update must not
    * read as the final reply, or the reflector concludes "final
    * response delivered" while the work is still in flight and every
    * later checkpoint inherits the false completion. */
  private final def renderInvokeHistoryLine(ti: sigil.event.ToolInvoke): String = {
    val tail = ti.outcome match {
      case sigil.event.ToolOutcome.Success       => "OK"
      case sigil.event.ToolOutcome.Failure(_, _) => "FAIL"
      case sigil.event.ToolOutcome.Pending       => "(no result yet)"
    }
    ti.input match {
      case Some(r: sigil.tool.model.RespondInput) =>
        val framing =
          if (r.endsTurn) "final reply"
          else "endsTurn = false — mid-task status update, NOT a final reply"
        s"${ti.toolName.value} ($framing) → \"${snippet(r.content, 80)}\" → $tail"
      case _ => s"${ti.toolName.value} → $tail"
    }
  }

  /** Timestamp of the agent's most recent settled [[sigil.event.ProgressCheckpoint]]
    * — the shared lower bound for the inter-checkpoint window that both
    * the reflection context and the stall detectors evaluate. */
  private final def priorCheckpointCutoff(convEvents: List[Event], agentId: ParticipantId): Option[Long] =
    convEvents.reverseIterator.collectFirst {
      case c: sigil.event.ProgressCheckpoint
        if c.participantId == agentId &&
           c.state == EventState.Complete =>
        c.timestamp.value
    }

  /** Evaluate the agent's recent tool-call tail for objective stall
    * signals — identical-call streaks and empty-payload streaks.
    * Folds into the progress checkpoint's `meaningfulProgress`
    * computation. Best-effort: failures fall through to the empty
    * signal rather than aborting the checkpoint. */
  /** Model-independent hard-stall check. Runs the input-only identical-call
    * streak at [[hardStallIdenticalCallLimit]] over the same since-checkpoint
    * tail [[evaluateStall]] uses. Returns the intervention reason when the
    * model has emitted the same call that many times in one turn — the signal
    * that every cooperative guard has been ignored and the turn must be force-
    * ended rather than ground to [[maxAgentIterations]]. Cheap (one event
    * read, no LLM), so it can run at every iteration boundary. */
  private[sigil] final def evaluateHardStall(convId: Id[Conversation],
                                             agentId: ParticipantId): Task[Option[String]] =
    if (hardStallIdenticalCallLimit <= 0) Task.pure(None)
    else loadStallRecords(convId, agentId).map { records =>
      sigil.conversation.compression.StallDetector
        .identicalInputStreak(records, hardStallIdenticalCallLimit)
        .reason
    }.handleError(_ => Task.pure(None))

  private final def evaluateStall(convId: Id[Conversation],
                                  agentId: ParticipantId): Task[sigil.conversation.compression.StallDetector.Signal] =
    loadStallRecords(convId, agentId)
      .map(sigil.conversation.compression.StallDetector.evaluate(_))
      .handleError(_ => Task.pure(sigil.conversation.compression.StallDetector.Signal.Empty))

  /** Build the chronological tail of non-internal tool calls since the prior
    * checkpoint (falling back to the most recent user Message, then 0) that
    * both [[evaluateStall]] and [[evaluateHardStall]] evaluate. */
  private final def loadStallRecords(convId: Id[Conversation],
                                     agentId: ParticipantId): Task[List[sigil.conversation.compression.StallDetector.CallRecord]] =
    withDB(_.conversationEvents(convId)).map { all =>
      val convEvents = all.iterator
        .collect { case e: Event if e.conversationId == convId => e }
        .toList
        .sortBy(_.timestamp.value)
      // Resolve the prior-checkpoint timestamp as the lower bound,
      // falling back to the most recent user Message when no prior
      // checkpoint exists, falling back to 0 otherwise.
      val cutoff = priorCheckpointCutoff(convEvents, agentId).orElse {
        convEvents.reverseIterator.collectFirst {
          case m: Message
            if !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId] &&
               m.role == MessageRole.Standard &&
               m.content.nonEmpty =>
            m.timestamp.value
        }
      }.getOrElse(0L)

      val invokes = convEvents.collect {
        case ti: sigil.event.ToolInvoke
          if ti.timestamp.value > cutoff &&
             ti.participantId == agentId &&
             !ti.internal => ti
      }
      val messagesByOrigin = convEvents.collect {
        case m: Message if m.role == MessageRole.Tool && m.origin.isDefined => m.origin.get -> m
      }.toMap
      val records = invokes.sortBy(_.timestamp.value).map { ti =>
        sigil.conversation.compression.StallDetector.CallRecord(
          invoke        = ti,
          resultMessage = messagesByOrigin.get(ti._id)
        )
      }
      records
    }

  /** Concatenate textual ResponseContent blocks; used to derive a
    * one-line view of a Message for the reflection prompt. */
  private final def textOfContent(blocks: Vector[_root_.sigil.tool.model.ResponseContent]): String =
    blocks.collect {
      case _root_.sigil.tool.model.ResponseContent.Text(t)     => t
      case _root_.sigil.tool.model.ResponseContent.Markdown(t) => t
    }.mkString(" ").trim

  private final def snippet(s: String, maxLen: Int): String =
    if (s.length <= maxLen) s else s.take(maxLen) + "…"

  /** Stitch the user task + tool history + prior checkpoint status
    * into the reflection prompt. Pure helper — useful to apps that
    * want to surface the same context shape to a custom reflection
    * tool, and to specs verifying the prompt structure. */
  def renderCheckpointPrompt(ctx: ProgressContext,
                             priorStatus: Option[String],
                             iteration: Int): String = {
    val taskBlock = ctx.userTask match {
      case Some(t) =>
        val directiveLine = ctx.latestDirective match {
          case Some(d) => s"The user has since said \"$d\" to continue this objective.\n\n"
          case None    => "\n"
        }
        s"The user's request:\n\"$t\"\n\n" + directiveLine
      case None    => "The user's request: (no recent substantive user message found)\n\n"
    }
    val earlierLine =
      if (ctx.earlierCalls > 0)
        s"(${ctx.earlierCalls} earlier calls preceded this window — judge progress on the window below.)\n"
      else ""
    val historyBlock = ctx.toolHistory match {
      case Nil => s"${earlierLine}What you've done since the last checkpoint: (no tool calls this window)\n\n"
      case list =>
        val numbered = list.zipWithIndex.map { case (line, i) => s"  ${i + 1}. $line" }.mkString("\n")
        s"${earlierLine}What you've done since the last checkpoint:\n$numbered\n\n"
    }
    val mutationsLine =
      if (ctx.windowMutations > 0)
        s"This window includes ${ctx.windowMutations} successful state-changing tool call(s) — " +
          "objective evidence that work was applied.\n\n"
      else ""
    val priorBlock = priorStatus match {
      case Some(s) => s"Prior checkpoint status: \"$s\"\n\n"
      case None    => "Prior checkpoint status: (first checkpoint)\n\n"
    }
    val ask =
      s"You are at iteration $iteration. " +
        s"Pick a one-line currentStatus describing where things stand RIGHT NOW, in this window's " +
        s"terms — never repeat the prior status verbatim; if nothing changed, say so plainly. Set " +
        s"meaningfulProgress = true ONLY when this window moved the task substantively past the prior status. " +
        s"One-line remainingSteps for what's left. Empty stuckOn unless you genuinely can't proceed. " +
        // #353 — a call shown as "OK" has COMPLETED; large results (images,
        // big reads) are stored out-of-line and won't appear inline. Only
        // "(no result yet)" is genuinely pending. Do not treat completed
        // calls as pending/processing — that false premise was stranding
        // turns behind a bogus clarification request.
        s"A tool call listed as \"OK\" SUCCEEDED — its result exists even if large and not shown here; " +
        s"only \"(no result yet)\" is still pending. Never set shouldAskUser because completed calls " +
        s"look resultless. shouldAskUser = true ONLY if the user must genuinely clarify something."
    taskBlock + historyBlock + mutationsLine + priorBlock + ask
  }

  /** Sigil bug #282 — return the agent's most recent
    * [[sigil.event.ProgressCheckpoint]] status text for this
    * conversation, formatted with stuck-on + remaining-steps when
    * present. Best-effort — failures fall through to None rather than
    * aborting the failure-publish path. */
  private[sigil] final def latestCheckpointStatus(agentId: ParticipantId,
                                           convId: Id[Conversation]): Task[Option[String]] =
    withDB(_.conversationEvents(convId)).map { events =>
      events.collect {
        case cp: sigil.event.ProgressCheckpoint if cp.participantId == agentId => cp
      }.maxByOption(_.timestamp.value).map { cp =>
        val statusLine = if (cp.currentStatus.nonEmpty) cp.currentStatus else "(no status text)"
        val stuckLine  = cp.stuckOn.filter(_.nonEmpty).map(s => s"\n\nStuck on: $s").getOrElse("")
        val nextLine   = if (cp.remainingSteps.nonEmpty) s"\n\nRemaining: ${cp.remainingSteps}" else ""
        s"$statusLine$stuckLine$nextLine"
      }
    }.handleError(_ => Task.pure(None))
}
