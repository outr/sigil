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
 * Model-routing cluster — everything that answers "which model runs
 * this call?". Owns the per-turn classifier memo and the per-turn
 * escalation counters, the work-type / complexity classification, the
 * escalate / de-escalate surface, strategy materialization, and the
 * routed-model resolvers (primary, candidate, auxiliary).
 *
 * Mixed into [[Sigil]]; the self-type reaches `withDB`, `cache`,
 * `resolveProviderModel`, and the notice channel the route-resolved
 * pulse rides.
 */
trait RoutingOps { this: Sigil =>

  /** The model id stamped on the most recent agent [[Message]] in
    * `conversationId`, when one exists. Reads `Message.modelId`
    * (populated by the orchestrator at settle-time from the resolved
    * `ConversationRequest.modelId`), so a mid-conversation pin /
    * strategy swap is reflected here.
    *
    * `None` for fresh conversations and for conversations where the
    * orchestrator hasn't yet stamped any agent Message (e.g. the
    * agent's only emissions so far are tool results). */
  def lastUsedModel(conversationId: Id[Conversation]): Task[Option[Id[Model]]] =
    withDB(_.eventsTransaction(conversationId)(_.list)).map { events =>
      events.iterator
        .collect { case m: sigil.event.Message if m.conversationId == conversationId => m }
        .filter(_.modelId.isDefined)
        .toList
        .sortBy(-_.timestamp.value)
        .headOption
        .flatMap(_.modelId)
    }

  // ---- Bug #128 / #167 — per-message routing state ----
  //
  // Split into two stores with disjoint concerns so mid-turn state
  // changes (pin, unpin, mode switch, etc.) can't shadow each other:
  //
  //   - classifierMemo  : pure memoization of the classifier LLM call.
  //                       Keyed by userMessageId (globally unique).
  //                       Value never mutates after write — same user
  //                       message implies the same classifier output.
  //                       Only purpose: avoid re-running the classifier
  //                       across the iterations of one agent turn.
  //
  //   - perTurnEscalations : per-turn mutable counter. Reset whenever
  //                          the conversation's userMessageId changes.
  //
  // Effective routing (pin vs classifier vs escalation) is computed
  // fresh on every `classifyForRoute` call from current conversation
  // state, so a mid-turn pin / unpin surfaces on the next iteration
  // without needing any cache invalidation logic.

  private val classifierMemo: java.util.concurrent.ConcurrentHashMap[Id[Event], (WorkType, Complexity)] =
    new java.util.concurrent.ConcurrentHashMap()

  private val perTurnEscalations: java.util.concurrent.ConcurrentHashMap[Id[Conversation], (Id[Event], Int)] =
    new java.util.concurrent.ConcurrentHashMap()

  // #371 — the human-readable reason (trigger + from→to tier) of the most
  // recent tier escalation, keyed by conversation and tagged with the turn's
  // userMessageId so a stale prior-turn reason isn't surfaced. Read by
  // `publishRouteResolved` so a frontier engagement is never silent.
  private val perTurnEscalationReason: java.util.concurrent.ConcurrentHashMap[Id[Conversation], (Id[Event], String)] =
    new java.util.concurrent.ConcurrentHashMap()

  /** The most recent tier-escalation reason recorded for `conversationId`
    * (trigger + from→to tier, e.g. "duplicate-call cap on `grep` (tier
    * High→VeryHigh, escalation #1)"), or `None` if no escalation has bumped the
    * tier. Surfaced on [[sigil.event.RouteResolved.escalationReason]] and
    * available to UIs that show WHY a frontier model was engaged — a frontier
    * engagement is never silent (sigil #371). */
  def escalationReasonFor(conversationId: Id[Conversation]): Option[String] =
    Option(perTurnEscalationReason.get(conversationId)).map(_._2)

  /** When `true`, the iteration-cap soft-stop auto-escalates
    * complexity one tier up for the forced-synthesis turn —
    * giving the recovery attempt the strongest available reasoning
    * in the chain. Logged via scribe. Default `false` to preserve
    * cost ceilings for apps that don't want auto-escalation. */
  def escalateOnCapHit: Boolean = false

  /** When `true` (default), the orchestrator's duplicate-call cap
    * ([[maxIdenticalToolCallsInWindow]]) bumps the conversation's
    * complexity tier one step on each cap trip, so the next iteration
    * routes to a more capable model that can read the Failure and pick
    * a different next move. Detection alone isn't enough on small
    * models — the same model that produced the duplicate keeps producing
    * it; escalation is what breaks the loop. Apps that pin a single tier
    * and don't want auto-bump set to `false`; the cap still fires
    * (Failure Message + refusal) but stays at the current tier. */
  def escalateOnDuplicateCallCap: Boolean = true

  /** Classify the user's latest message for this conversation,
    * caching the result for the lifetime of that user turn. Returns
    * `(WorkType, Complexity)` — the routing key the framework
    * matches against [[ModelCandidate.supportedComplexity]] when
    * picking a candidate.
    *
    * Skip gates (cheapest-first):
    *   - Strategy didn't supply [[ProviderStrategy.inferWorkType]] /
    *     [[ProviderStrategy.inferComplexity]] → use `defaultWorkType`
    *     / `Complexity.Medium`.
    *   - Strategy's [[ProviderStrategy.workTypeMatters]] /
    *     [[ProviderStrategy.complexityMatters]] is false → skip the
    *     classifier; outcome can't change the candidate.
    *
    * On classifier failure (network, unparsable response, etc.) the
    * routing falls back to defaults rather than blocking the turn.
    *
    * Memo is keyed by `userMessageId` — the classifier output is a
    * function of the user's message text and never changes within
    * one turn. Pin / unpin / escalation read fresh from the
    * conversation + per-turn escalation counter, so mid-turn tier
    * changes surface on the next call without any cache machinery
    * to coordinate.
    */
  def classifyForRoute(strategy: ProviderStrategy,
                       defaultWorkType: WorkType,
                       conversation: sigil.conversation.Conversation,
                       userMessage: Option[sigil.event.Message],
                       turnContext: sigil.TurnContext): Task[(WorkType, Complexity)] = {
    val userMsg = userMessage
    val userText = userMsg.flatMap(_.content.collect {
      case ResponseContent.Text(t)     => t
      case ResponseContent.Markdown(t) => t
    }.headOption).getOrElse("")
    val msgId = userMsg.map(_._id).getOrElse(Event.id())

    // Reset the per-turn escalation counter when the user turn
    // advances. Done eagerly so `requestEscalation` and the
    // RouteResolved escalation read both see the right turn-scope.
    perTurnEscalations.compute(conversation._id, (_, existing) =>
      if (existing == null || existing._1 != msgId) (msgId, 0) else existing
    )

    // Memo: classifier output for this user message. Pure function
    // of (userText), so safe to memoize across the iterations of
    // this turn. Compute once, re-use.
    val memoed = Option(classifierMemo.get(msgId))
    val classifierTask: Task[(WorkType, Complexity)] = memoed match {
      case Some(v) => Task.pure(v)
      case None    =>
        val wtTask: Task[WorkType] =
          if (strategy.shouldClassifyWorkType && userText.nonEmpty)
            strategy.inferWorkType.get.apply(userText, turnContext)
              .handleError { e =>
                scribe.warn(s"inferWorkType failed (${e.getClass.getSimpleName}: ${e.getMessage}) — falling back to ${defaultWorkType}")
                Task.pure(defaultWorkType)
              }
          else Task.pure(defaultWorkType)
        wtTask.flatMap { wt =>
          val cxTask: Task[Complexity] =
            if (strategy.shouldClassifyComplexity(wt) && userText.nonEmpty)
              strategy.inferComplexity.get.apply(userText, turnContext)
                .handleError { e =>
                  scribe.warn(s"inferComplexity failed (${e.getClass.getSimpleName}: ${e.getMessage}) — falling back to ${strategy.defaultComplexity}")
                  Task.pure(strategy.defaultComplexity)
                }
            else Task.pure(strategy.defaultComplexity)
          cxTask.map { cx =>
            val v = (wt, cx)
            classifierMemo.putIfAbsent(msgId, v)
            v
          }
        }
    }

    // Effective routing: fresh derivation from current state.
    // Pin wins over inference. Escalations apply on top of the
    // classifier complexity. Pin and escalations are independent —
    // when a pin is in effect, escalations are intentionally ignored
    // so the pinned tier stays binding for the duration of the turn.
    classifierTask.map { case (wt, classifierCx) =>
      val effectiveCx = conversation.pinnedComplexity match {
        case Some(pinned) => pinned
        case None =>
          val escalations = Option(perTurnEscalations.get(conversation._id)).map(_._2).getOrElse(0)
          (1 to escalations).foldLeft(classifierCx)((acc, _) => Complexity.bumpUp(acc))
      }
      (wt, effectiveCx)
    }
  }

  /** Bump the per-turn complexity tier one step up for the current
    * user turn — what [[sigil.tool.core.RequestEscalationTool]] calls
    * when the agent realizes mid-turn that the task is harder than
    * the classifier's initial assessment. Returns `(newTier, bumped)`:
    *
    *   - `bumped = true` means the tier actually moved (Low → Medium
    *     or Medium → High);
    *   - `bumped = false` means we were already at High (clamp) or
    *     no classification has been done yet (no message id to
    *     attach the escalation to).
    *
    * The escalation count is held in [[perTurnEscalations]] keyed
    * by conversation; subsequent calls to [[classifyForRoute]] apply
    * the count on top of the classifier's raw complexity to produce
    * the effective tier. */
  def requestEscalation(conversationId: Id[Conversation], reason: String): Task[(Complexity, Boolean)] = Task {
    val state = perTurnEscalations.get(conversationId)
    if (state == null) (Complexity.Medium, false)
    else {
      val (msgId, count) = state
      // Compute the would-be effective tier from the memo + new count.
      // The memo's complexity may not exist yet if shouldClassifyComplexity
      // is false (e.g., the strategy doesn't have a classifier); in that
      // case bump from defaultComplexity-equivalent Medium as a safe baseline.
      val classifierCx = Option(classifierMemo.get(msgId)).map(_._2).getOrElse(Complexity.Medium)
      val currentEffective = (1 to count).foldLeft(classifierCx)((acc, _) => Complexity.bumpUp(acc))
      val nextEffective = Complexity.bumpUp(currentEffective)
      val bumped = nextEffective != currentEffective
      if (bumped) {
        perTurnEscalations.put(conversationId, (msgId, count + 1))
        perTurnEscalationReason.put(conversationId,
          (msgId, s"$reason (tier $currentEffective→$nextEffective, escalation #${count + 1})"))
      }
      scribe.info(s"requestEscalation conv=${conversationId.value} from=$currentEffective to=$nextEffective bumped=$bumped reason=$reason")
      (nextEffective, bumped)
    }
  }.flatMap { result =>
    // Escalation-headroom check: bumping the tier with the turn
    // already ≥ half its soft budget means the expensive iterations
    // are about to run into a near-exhausted budget — flag the claim
    // so the NEXT boundary fires the soft check-in immediately (the
    // user gets asked BEFORE the pricey tier grinds, not after).
    val (_, bumped) = result
    if (!bumped) Task.pure(result)
    else withDB(_.conversations.transaction(_.get(conversationId))).map {
      case Some(conv) =>
        effectiveBudgetsFor(conv).turnSoft.foreach { soft =>
          conv.participants.collect { case a: AgentParticipant => a }.foreach { a =>
            Option(activeClaims.get(agentStateLockId(a.id, conversationId))).foreach { entry =>
              if (entry.turnCost * 2 >= soft && !entry.turnSoftFired.get()) {
                entry.budgetCheckinRequested.set(true)
              }
            }
          }
        }
        result
      case None => result
    }.handleError(_ => Task.pure(result))
  }

  /** Step the current user turn's escalation count back DOWN one
    * level — what [[sigil.tool.core.RequestDeescalationTool]] calls
    * when the agent judges the remaining work mechanical enough for a
    * cheaper tier. Returns `(newTier, lowered)`; `lowered = false`
    * when there was no escalation to unwind (the counter is already
    * at the classifier's base tier — de-escalating BELOW the
    * classified tier is the pin's job, not this counter's). Across
    * turns no unwinding is needed: the counter resets to zero at
    * every new user message. */
  def requestDeescalation(conversationId: Id[Conversation], reason: String): Task[(Complexity, Boolean)] = Task {
    val state = perTurnEscalations.get(conversationId)
    if (state == null) (Complexity.Medium, false)
    else {
      val (msgId, count) = state
      val classifierCx = Option(classifierMemo.get(msgId)).map(_._2).getOrElse(Complexity.Medium)
      val currentEffective = (1 to count).foldLeft(classifierCx)((acc, _) => Complexity.bumpUp(acc))
      if (count <= 0) (currentEffective, false)
      else {
        val nextEffective = (1 to (count - 1)).foldLeft(classifierCx)((acc, _) => Complexity.bumpUp(acc))
        perTurnEscalations.put(conversationId, (msgId, count - 1))
        perTurnEscalationReason.put(conversationId,
          (msgId, s"$reason (tier $currentEffective→$nextEffective, de-escalation)"))
        scribe.info(s"requestDeescalation conv=${conversationId.value} from=$currentEffective to=$nextEffective reason=$reason")
        (nextEffective, true)
      }
    }
  }

  /** Internal hook for the cap-hit forced-synthesis path. Bumps the
    * cached tier when [[escalateOnCapHit]] is true; no-op otherwise.
    * The bumped tier flows through the next candidate-resolution
    * call (the forced-synthesis turn) so the recovery attempt runs
    * against a more capable model. */
  protected[sigil] def escalateForCapHit(conversationId: Id[Conversation]): Task[Unit] =
    if (!escalateOnCapHit) Task.unit
    else requestEscalation(conversationId, reason = "iteration-cap forced synthesis").map(_ => ())

  /** Emit a [[sigil.event.RouteResolved]] event capturing the
    * per-turn routing decision. Includes the classifier output (or
    * `None` when the framework defaulted), the candidate chain
    * considered, which candidate won, and per-candidate skip
    * reasons. Best-effort: emission failures are swallowed so a
    * forensic-channel hiccup never blocks the turn itself. */
  private[sigil] final def publishRouteResolved(agentId: ParticipantId,
                                         conversation: Conversation,
                                         userMessage: Option[sigil.event.Message],
                                         strategyOpt: Option[ProviderStrategy],
                                         inferredWorkType: WorkType,
                                         complexity: Complexity,
                                         candidateChain: List[Id[Model]],
                                         chosenModelId: Id[Model],
                                         skipReasons: Map[Id[Model], String]): Task[Unit] = {
    val classifierFired = strategyOpt.exists(_.shouldClassifyWorkType) ||
      strategyOpt.exists(_.shouldClassifyComplexity(inferredWorkType))
    val event = sigil.event.RouteResolved(
      participantId       = agentId,
      conversationId      = conversation._id,
      topicId             = conversation.currentTopicId,
      userMessageId       = userMessage.map(_._id),
      inferredWorkType    = if (strategyOpt.exists(_.shouldClassifyWorkType)) Some(inferredWorkType) else None,
      inferredComplexity  = if (strategyOpt.exists(_.shouldClassifyComplexity(inferredWorkType))) Some(complexity) else None,
      candidateChain      = candidateChain,
      chosenModelId       = chosenModelId,
      skipReasons         = skipReasons,
      classifierLatencyMs = None,
      escalationCount     = Option(perTurnEscalations.get(conversation._id)).map(_._2).getOrElse(0),
      // Surface the escalation reason only when it belongs to THIS turn (its
      // stored userMessageId matches), so a stale prior-turn note never leaks.
      escalationReason    = Option(perTurnEscalationReason.get(conversation._id))
        .filter { case (mid, _) => userMessage.map(_._id).contains(mid) }
        .map(_._2)
    )
    publish(event).map(_ => ()).handleError(_ => Task.unit)
  }

  /** Resolve the model id this conversation would dispatch to on the
    * next turn — the same lookup chain `runAgentTurn` uses, exposed as
    * a read-only helper for introspection (e.g. [[CurrentModelTool]]).
    * Order: [[sigil.conversation.Conversation.pinnedModelId]] →
    * [[sigil.provider.Mode.strategyId]] →
    * [[resolveProviderStrategy]] for the conversation's space → first
    * candidate for the conversation's effective work type. Returns
    * `None` when no resolution layer applies (a deeply un-configured
    * Sigil where dispatch would itself error). */
  def currentModelFor(conversation: sigil.conversation.Conversation): Task[Option[Id[Model]]] = {
    val workType = conversation.currentMode.workType.getOrElse(sigil.provider.ConversationWork)
    conversation.pinnedModelId match {
      case Some(pinned) => Task.pure(Some(pinned))
      case None =>
        conversation.currentMode.strategyId match {
          case Some(modeStrategyId) =>
            withDB(_.providerStrategies.transaction(_.get(modeStrategyId)))
              .map(_.map(materializeStrategy).flatMap(_.availableCandidates(workType).headOption.map(_.modelId)))
          case None =>
            resolveProviderStrategy(conversation.space)
              .map(_.flatMap(_.availableCandidates(workType).headOption.map(_.modelId)))
        }
    }
  }

  /** Default record → strategy materializer. Override to swap in a
    * custom [[sigil.provider.ProviderStrategy]] (round-robin,
    * cost-aware routing, etc.) using the persisted record as a
    * config knob. */
  protected def materializeStrategy(record: sigil.provider.ProviderStrategyRecord): sigil.provider.ProviderStrategy = {
    // routeCandidates is keyed by `WorkType.value` strings; resolve
    // each through the registered WorkType polytype names. Unregistered
    // values fall through to a synthetic `WorkType` with that string.
    val routes: Map[sigil.provider.WorkType, List[sigil.provider.ModelCandidate]] =
      record.routeCandidates.flatMap { case (key, list) =>
        // Try framework-shipped subtypes first; fall back to a one-off
        // anonymous WorkType so dispatch can still match if the app's
        // strategy uses values the framework doesn't know about.
        val wt: sigil.provider.WorkType = key.toLowerCase match {
          case "conversation"   => sigil.provider.ConversationWork
          case "coding"         => sigil.provider.CodingWork
          case "analysis"       => sigil.provider.AnalysisWork
          case "classification" => sigil.provider.ClassificationWork
          case "creative"       => sigil.provider.CreativeWork
          case "summarization"  => sigil.provider.SummarizationWork
          case other            => new sigil.provider.WorkType { override val value: String = other }
        }
        Map(wt -> list)
      }
    sigil.provider.ProviderStrategy.routed(record.defaultCandidates, routes)
  }

  /** Pick a model for `workType`, scoped to `chain`. Default impl walks
    * the chain's accessible spaces, resolves each space's
    * [[sigil.provider.ProviderStrategy]], and returns the first
    * available candidate for `workType` whose `Model.contextLength`
    * can accommodate `estimatedInputTokens + reservedOutputTokens`.
    * Falls back to `fallback` when no strategy applies or no candidate
    * fits.
    *
    * Bug #26 — used by the framework's compressor to route
    * summarization through a `SummarizationWork`-tier model rather
    * than inheriting the calling agent's modelId.
    *
    * Bug #41 — `estimatedInputTokens` lets callers skip candidates
    * whose context window can't physically fit the request, so a
    * cost-aware chain like `[llama (32K), gpt-5.5, claude]` does the
    * right thing automatically: small input → llama; oversized input
    * → fall through to a frontier candidate. `None` (default) keeps
    * the legacy head-first behavior for callers that have no size
    * signal. Candidates whose `Model.contextLength` isn't in the
    * cache are NOT skipped (treated as "size unknown" → keep) so
    * apps with custom-provider models lacking a registered
    * contextLength aren't broken.
    *
    * `reservedOutputTokens` is the budget reserved for the response
    * — added to `estimatedInputTokens` when measuring fit. Default
    * 1024 is enough for typical summary outputs; callers expecting
    * larger responses pass higher.
    *
    * Apps override for custom routing (e.g. cost-aware fallback
    * ordering, sticky model preferences). */
  def routedModelFor(workType: sigil.provider.WorkType,
                     chain: List[ParticipantId],
                     fallback: Id[Model],
                     estimatedInputTokens: Option[Long] = None,
                     reservedOutputTokens: Long = 1024L,
                     // Sigil #289 — optional complexity hint. When set,
                     // candidates whose `supportedComplexity` doesn't
                     // include this tier are filtered out before the
                     // first-fit pick. When None (the default), no
                     // complexity filtering applies (preserves prior
                     // behaviour for every existing caller). The
                     // primary motivator is `delegate_task` letting
                     // the spawning agent express "give the worker a
                     // High-tier model" without having to enumerate a
                     // specific modelId.
                     complexity: Option[sigil.provider.Complexity] = None): Task[Id[Model]] = {
    routedCandidateFor(workType, chain, estimatedInputTokens, reservedOutputTokens, complexity)
      .map(_.map(_.modelId).getOrElse(fallback))
  }

  /**
   * Like [[routedModelFor]], but returns the resolved [[sigil.provider.ModelCandidate]]
   * itself (not just its id) so callers can carry its per-model
   * [[GenerationSettings]] — token cap, `reasoningMode`, etc. `None` when no
   * strategy / candidate matches (the caller should fall back to the running
   * agent's own model + settings). `routedModelFor` is the id-only convenience
   * over this. Surfacing the candidate matters wherever a request is built
   * outside the agent's normal turn (e.g. a workflow leaf prompt), since
   * dropping the candidate's settings re-opens the reasoning-runaway / no-cap
   * failure those settings exist to prevent.
   */
  def routedCandidateFor(workType: sigil.provider.WorkType,
                         chain: List[ParticipantId],
                         estimatedInputTokens: Option[Long] = None,
                         reservedOutputTokens: Long = 1024L,
                         complexity: Option[sigil.provider.Complexity] = None): Task[Option[sigil.provider.ModelCandidate]] = {
    val convId: Id[Conversation] = sigil.conversation.Conversation.id("__no_conv__")
    val required = estimatedInputTokens.map(_ + reservedOutputTokens)

    def fits(candidate: sigil.provider.ModelCandidate): Boolean = required match {
      case None => true
      case Some(needed) =>
        cache.find(candidate.modelId).map(_.contextLength) match {
          // contextLength unknown → don't filter (custom provider /
          // stale registry). Caller still gets a candidate; if it
          // overflows downstream, the compressor's chunk-and-merge
          // fallback in `compressLarge` handles it.
          case None       => true
          case Some(0L)   => true
          case Some(ctx)  => needed <= ctx
        }
    }

    // #315 — degrade to the nearest available tier AT OR BELOW the
    // requested one (High → Medium → Low), not cratering to the pinned
    // fallback when the exact tier has no candidate. `None` complexity
    // keeps the legacy first-fit behaviour. Down-only by default: a
    // High request never silently escalates to a VeryHigh candidate.
    def pickFrom(avail: List[sigil.provider.ModelCandidate]): Option[sigil.provider.ModelCandidate] =
      complexity match {
        case None => avail.find(fits)
        case Some(requested) =>
          sigil.provider.Complexity.atOrBelow(requested).iterator
            .flatMap(tier => avail.filter(_.supportedComplexity.contains(tier)).find(fits))
            .nextOption()
      }

    accessibleSpaces(chain, convId).flatMap { spaces =>
      val ordered = spaces.toList
      def loop(remaining: List[SpaceId]): Task[Option[sigil.provider.ModelCandidate]] = remaining match {
        case Nil => Task.pure(None)
        case space :: rest =>
          resolveProviderStrategy(space).flatMap {
            case None => loop(rest)
            case Some(strategy) =>
              pickFrom(strategy.availableCandidates(workType)) match {
                case Some(candidate) => Task.pure(Some(candidate))
                case None            => loop(rest)
              }
          }
      }
      loop(ordered)
    }
  }

  /** #357 — whether a conversation's
    * [[sigil.conversation.Conversation.pinnedModelId]] also governs the
    * framework's auxiliary LLM calls (topic classifier, memory
    * extractor, progress checkpoint, curate compression).
    *
    * Default `false` (cost-first): a pin governs only the agent's main
    * turn; auxiliary calls route through [[routedModelFor]] to the
    * cheapest viable tier for their work type, independent of the pin —
    * which is usually the right default (classification / extraction /
    * summarization don't need a frontier model). Apps that want a pin to
    * mean "every LLM call in this conversation" override to `true`. */
  def pinCoversAuxiliaryCalls: Boolean = false

  /** #357 — auxiliary-call model resolution. When
    * [[pinCoversAuxiliaryCalls]] is `false` (default) this is exactly
    * [[routedModelFor]] — cost-first routing for `workType`, ignoring any
    * conversation pin. When `true` and the conversation carries a
    * [[sigil.conversation.Conversation.pinnedModelId]], the pin wins
    * (honoring the "pin = every LLM call" contract); otherwise it falls
    * back to `routedModelFor`. The conversation read only happens when
    * the knob is on, so the default path adds no DB cost. */
  def auxModelFor(conversationId: Id[Conversation],
                  workType: sigil.provider.WorkType,
                  chain: List[ParticipantId],
                  fallback: Id[Model],
                  estimatedInputTokens: Option[Long] = None,
                  reservedOutputTokens: Long = 1024L,
                  complexity: Option[sigil.provider.Complexity] = None): Task[Id[Model]] =
    if (!pinCoversAuxiliaryCalls)
      routedModelFor(workType, chain, fallback, estimatedInputTokens, reservedOutputTokens, complexity)
    else
      withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
        case Some(conv) if conv.pinnedModelId.isDefined => Task.pure(conv.pinnedModelId.get)
        case _ => routedModelFor(workType, chain, fallback, estimatedInputTokens, reservedOutputTokens, complexity)
      }
}
