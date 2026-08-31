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
import sigil.conversation.{
  ActiveSkillSlot, ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, EncodedContext, FrameBuilder, MemorySource,
  MemoryStatus, ParticipantProjection, ProgressContext, SkillSource, ToolCallState, Topic, TopicEntry, TopicShiftResult, TurnInput,
  TurnPlan, UpsertMemoryResult
}
import sigil.SpaceId
import sigil.cache.ModelRegistry
import sigil.controller.OpenRouter
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.governor.{BudgetDirective, BudgetGovernor, CheckpointIntervention, GovernorContext}
import sigil.governor.{
  DegenerateGenerationGovernor, GovernorVote, OutcomeGovernor, PlainTextReplyGovernor,
  ProgressGovernor, TurnDecisionGovernor, TurnGovernor
}
import sigil.transport.SignalTransport

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sigil.tool.consult.{ConsultTool, TopicClassifierTool}
import sigil.provider.{GenerationSettings, TokenUsage}
import sigil.db.{DefaultSigilDB, Model, SigilDB}
import sigil.dispatcher.{StopFlag, TriggerFilter}
import sigil.event.{
  AgentState, CapabilityResults, Event, EventsPage, Message, MessageRole, MessageVisibility, ModeChange, Stop, ToolInvoke, TopicChange,
  TopicChangeKind
}
import sigil.role.Role
import sigil.orchestrator.{BudgetScope, Directive, Orchestrator}
import sigil.provider.{Complexity, ConversationMode, ConversationRequest, Mode, ProviderStrategy, ReasoningMode, ToolPolicy, WorkType}
import sigil.information.Information
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.pipeline.{
  ContentExternalizationTransform, GeocodingEnrichmentEffect, InboundTransform, LocationCaptureTransform, MemoryCacheInvalidationEffect,
  MessageIndexingEffect, RedactLocationTransform, RespondOptionsSelectionFramingTransform, SettledEffect, SignalHub,
  TopicIndexCanonicalizingTransform, ViewerTransform, WorkerConversationAddressingTransform
}
import sigil.render.{ContentRenderer, HtmlRenderer, MarkdownRenderer, PlainTextRenderer, SlackMrkdwnRenderer}
import sigil.provider.Provider
import sigil.provider.{ContextSection, ContextSections, InstructionTier, ModelProfile, PromptShape, Reliability, ResolvedReferences}
import sigil.service.Service
import sigil.signal.{
  AgentActivity, AgentStateDelta, CoreSignals, Delta, EventState, LocationDelta, Notice, ServiceLogSignal, ServiceStatusSignal, Signal,
  ToolDelta, TopicDelta
}
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
 * Internal-directive cluster — the framework's own voice inside a
 * conversation. Evaluates the spend budgets at each iteration
 * boundary, publishes the synthetic-diagnostic invoke + Tool-role
 * Message pair that carries a [[sigil.orchestrator.Directive]] to the
 * agent's next iteration, and owns the one-shot user-visible notice
 * fired when a conversation hits its hard ceiling.
 *
 * Mixed into [[Sigil]]; the self-type reaches `activeClaims`,
 * `effectiveBudgetsFor`, the checkpoint latch, and `publish`.
 */
trait DirectiveOps { this: Sigil =>

  /**
   * One user-visible notice per conversation-hard-ceiling
   * exhaustion. The latch clears when `set_budget` raises the
   * budget, so repeated triggers while exhausted don't spam and a
   * raised budget re-arms cleanly.
   */
  final private val budgetExhaustedNotified: ConcurrentHashMap[Id[Conversation], java.lang.Boolean] =
    new ConcurrentHashMap()

  final private[sigil] def clearBudgetExhaustedNotice(conversationId: Id[Conversation]): Unit = {
    budgetExhaustedNotified.remove(conversationId)
    ()
  }

  final private[sigil] def notifyBudgetExhausted(agent: AgentParticipant, conv: Conversation): Task[Unit] =
    if (budgetExhaustedNotified.putIfAbsent(conv._id, java.lang.Boolean.TRUE) != null) Task.unit
    else {
      val ceiling = effectiveBudgetsFor(conv).conversationHard.map(c => f"$$$c%.2f").getOrElse("?")
      publish(Message(
        participantId = agent.id,
        conversationId = conv._id,
        topicId = conv.currentTopicId,
        content = Vector(_root_.sigil.tool.model.ResponseContent.Text(
          f"This conversation has reached its spend ceiling ($ceiling; $$${conv.cost}%.2f spent) — the agent will " +
            "not run further turns here. Raise the budget (the `set_budget` capability, e.g. \"set this " +
            "conversation's budget to $10\") or start a new conversation.")),
        state = EventState.Complete,
        disposition = sigil.event.MessageDisposition.Failure(recoverable = true)
      )).unit
    }

  /**
   * Evaluate the spend budgets at an iteration boundary. Hard
   * ceilings (turn, then conversation) win; soft crossings fire
   * once per claim per scope; the escalation-headroom flag forces
   * the soft check-in early. `None` = under budget (or budgets
   * unset) — zero behavior change.
   */
  final private[sigil] def evaluateBudgetGate(conv: Conversation, claimed: AgentState): Task[Option[BudgetDirective]] = Task {
    Option(activeClaims.get(claimed._id)).flatMap { entry =>
      val budgets = effectiveBudgetsFor(conv)
      val turnCost = entry.turnCost
      val convCost = entry.conversationCostAtClaim + turnCost
      val turnHard = budgets.turnHard.filter(turnCost >= _).map(limit =>
        BudgetDirective(
          hard = true,
          Directive.BudgetCeiling(turnCost, convCost, BudgetScope.PerTurn, limit)))
      val convHard = budgets.conversationHard.filter(convCost >= _).map(limit =>
        BudgetDirective(
          hard = true,
          Directive.BudgetCeiling(turnCost, convCost, BudgetScope.Conversation, limit)))
      def softFor(limit: BigDecimal,
                  scope: BudgetScope,
                  latch: java.util.concurrent.atomic.AtomicBoolean,
                  crossed: Boolean): Option[BudgetDirective] =
        if (crossed && latch.compareAndSet(false, true))
          Some(BudgetDirective(hard = false, Directive.BudgetCheckin(turnCost, convCost, scope, limit)))
        else None
      val headroom = entry.budgetCheckinRequested.compareAndSet(true, false)
      val turnSoft = budgets.turnSoft.flatMap(limit =>
        softFor(
          limit,
          BudgetScope.PerTurn,
          entry.turnSoftFired,
          crossed = turnCost >= limit || (headroom && turnCost > 0)))
      val convSoft = budgets.conversationSoft.flatMap(limit =>
        // Conversation-soft fires on the CROSSING (this turn pushed the
        // total over), not on every turn that starts already above it —
        // the user's continuing messages after a check-in are the
        // approval.
        softFor(
          limit,
          BudgetScope.Conversation,
          entry.conversationSoftFired,
          crossed = entry.conversationCostAtClaim < limit && convCost >= limit))
      val directive = turnHard.orElse(convHard).orElse(turnSoft).orElse(convSoft)
      // With the planner tier enabled, a soft check-in is also an
      // anomaly signal: arm the next checkpoint boundary's planner
      // consult so the spend spike gets a strategy review. The budget
      // directive itself is unchanged.
      if (plannerModelId.isDefined && directive.exists(!_.hard)) {
        checkpointStates.computeIfAbsent(
          claimed._id,
          _ => CheckpointState(lastStatus = None, noProgressStreak = 0)).plannerAnomalyPending = true
      }
      directive
    }
  }

  /**
   * Publish an internal framework directive on the stall-nudge
   * channel: a synthetic internal invoke + a Tool-role,
   * Agents-visibility Message the agent reads next iteration. Used by
   * the budget gate (`_budget_ceiling` / `_budget_checkin`) and the
   * planner checkpoint (`_plan` / `_planner_correction`).
   */
  final private[sigil] def publishInternalDirective(agent: AgentParticipant,
                                                    conv: Conversation,
                                                    d: sigil.orchestrator.Directive): Task[Unit] =
    publishInternalDirective(agent, conv, d, d.render)

  /**
   * Publish a directive whose prose was authored elsewhere (the
   * checkpoint path adopts an intervention's own message content).
   * The synthetic invoke still carries the typed payload.
   */
  final private[sigil] def publishInternalDirective(agent: AgentParticipant,
                                                    conv: Conversation,
                                                    d: sigil.orchestrator.Directive,
                                                    text: String): Task[Unit] = {
    val syntheticInvoke = sigil.orchestrator.SyntheticDiagnostic
      .invoke(d, agent.id, conv._id, conv.currentTopicId)
    val directive = Message(
      participantId = agent.id,
      conversationId = conv._id,
      topicId = conv.currentTopicId,
      content = Vector(_root_.sigil.tool.model.ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.Agents,
      origin = Some(syntheticInvoke._id)
    )
    publish(syntheticInvoke).flatMap(_ => publish(directive)).unit
  }
}
