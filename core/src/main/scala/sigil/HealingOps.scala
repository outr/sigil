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
 * Reactive self-heal pipeline — the entry point the agent loop's
 * `handleError` calls when an iteration throws. Matches the thrown
 * error against the registered [[sigil.heal.HealingStrategy]] list,
 * publishes the durable corruption / healed / exhausted triple plus
 * the transient activity pulses, and hands back the retry task when
 * the heal applied.
 *
 * Mixed into [[Sigil]]; the self-type reaches `healingStrategies`,
 * `healingMode`, `withDB`, and `publish`.
 */
trait HealingOps { this: Sigil =>

  /**
   * Sigil #313 — reactive self-heal entry point invoked from the
   * agent loop's `handleError`.
   *
   * Returns `Some(retryTask)` when the heal applied and the agent
   * loop should run one more iteration (the retry); `None` when the
   * standard failure path should take over (no strategy matched, or
   * we've already healed this turn, or strict mode refused).
   *
   * Publishes a durable triple — [[sigil.event.ConversationCorruptionDetected]]
   * before the strategy runs, [[sigil.event.ConversationHealed]] /
   * [[sigil.event.HealingExhausted]] after — plus operational
   * [[sigil.signal.HealingActivityNotice]] pulses so log aggregators
   * see both the audit (durable) and alerting (transient) channels.
   *
   * Best-effort by design: a heal-pipeline DB / hub failure must
   * not mask the original error. Every publish runs with
   * `.handleError(_ => Task.unit)` so the worst case is "we lost
   * the audit pulse, the original failure still surfaces".
   */
  final private[sigil] def tryHealAgentLoopError(agent: AgentParticipant,
                                                 convId: Id[Conversation],
                                                 claimed: AgentState,
                                                 thrown: Throwable,
                                                 healedThisTurn: java.util.concurrent.atomic.AtomicBoolean,
                                                 healCorrelationId: AtomicReference[Option[String]]): Task[Option[Task[Unit]]] = {
    val matchedOpt: Option[sigil.heal.HealingStrategy] = healingStrategies.find(_.matches(thrown))
    matchedOpt match {
      case None => Task.pure(None)
      case Some(strategy) =>
        val mode = healingMode
        // `scribe.error` the original error with full payload BEFORE
        // any audit publishes — log aggregators see the upstream
        // failure regardless of what the audit pipeline does next.
        scribe.error(
          s"heal[${strategy.name}] caught ${thrown.getClass.getSimpleName} on " +
            s"${agent.id.value}/${convId.value}: ${Option(thrown.getMessage).getOrElse("(no message)")}",
          thrown
        )
        val evidence = strategy.detect(thrown)
        val errorEvidence = sigil.event.ErrorEvidence.of(thrown)
        val correlation: String = healCorrelationId.updateAndGet { current =>
          current.orElse(Some(rapid.Unique().take(8)))
        }.getOrElse(rapid.Unique().take(8))
        // Always publish CorruptionDetected — both Recover and Strict
        // paths record the corruption. Strict refuses the heal but
        // the durable trail must show the failure was seen.
        val detectorSource: String = thrown match {
          case _: sigil.heal.BrokenHistoryException => "renderFrames-invariant"
          case _ => "provider-call"
        }
        val publishDetected: Task[Unit] = withDB(_.conversations.transaction(_.get(convId))).flatMap {
          case None => Task.unit
          case Some(conv) =>
            conv.topics.headOption match {
              case None => Task.unit
              case Some(topic) =>
                publish(sigil.event.ConversationCorruptionDetected(
                  conversationId = convId,
                  topicId = topic.id,
                  detectorSource = detectorSource,
                  originalError = errorEvidence,
                  correlationId = correlation,
                  modelId = Some(agent.modelId),
                  detectedCorruption = evidence,
                  participantId = agent.id
                )).map(_ => ())
            }
        }.handleError { e =>
          scribe.warn(s"heal[${strategy.name}] publishDetected failed for ${convId.value}: " +
            s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
          Task.unit
        }

        mode match {
          case sigil.heal.HealingMode.Strict =>
            // Record corruption, fire StrictRefused notice, fall
            // through to the existing failure path (the original
            // throwable re-raises through the agent loop's standard
            // path).
            val notify: Task[Unit] = Task {
              hub.emit(sigil.signal.HealingActivityNotice(
                conversationId = convId,
                strategyName = strategy.name,
                detectedCorruption = evidence,
                outcome = sigil.heal.HealingOutcome.StrictRefused
              ))
              ()
            }.handleError(_ => Task.unit)
            publishDetected.flatMap(_ => notify).map(_ => None)
          case sigil.heal.HealingMode.Recover =>
            // Publish a terminal `HealingExhausted` event for this
            // turn. Shared by the retry-also-failed path and the
            // no-op-heal path (Sigil #314) — both are terminal "the
            // heal couldn't recover this turn" outcomes that must
            // fall through to the standard failure path.
            val publishExhausted: Task[Unit] = withDB(_.conversations.transaction(_.get(convId))).flatMap {
              case None => Task.unit
              case Some(conv) =>
                conv.topics.headOption match {
                  case None => Task.unit
                  case Some(topic) =>
                    publish(sigil.event.HealingExhausted(
                      conversationId = convId,
                      topicId = topic.id,
                      correlationId = correlation,
                      strategyName = strategy.name,
                      retryError = errorEvidence,
                      participantId = agent.id
                    )).map(_ => ())
                }
            }.handleError(_ => Task.unit)
            def notifyOutcome(outcome: sigil.heal.HealingOutcome): Task[Unit] = Task {
              hub.emit(sigil.signal.HealingActivityNotice(
                conversationId = convId,
                strategyName = strategy.name,
                detectedCorruption = evidence,
                outcome = outcome
              ))
              ()
            }.handleError(_ => Task.unit)
            if (!healedThisTurn.compareAndSet(false, true)) {
              // We already healed this turn AND the retry failed.
              // Publish HealingExhausted + Exhausted notice and fall
              // through. NO second heal.
              publishExhausted.flatMap(_ => notifyOutcome(sigil.heal.HealingOutcome.Exhausted)).map(_ => None)
            } else {
              // First heal this turn — publish Detected, run strategy,
              // publish Healed + Healed notice, return retry task.
              val runStrategy: Task[sigil.heal.HealResult] =
                strategy.apply(evidence, convId, this).handleError { e =>
                  scribe.error(
                    s"heal[${strategy.name}] strategy.apply threw on " +
                      s"${agent.id.value}/${convId.value}",
                    e)
                  Task.pure(sigil.heal.HealResult(
                    corrections = Nil,
                    remainingIssues = List(s"strategy.apply threw ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
                  ))
                }
              val publishHealed: sigil.heal.HealResult => Task[Unit] = result =>
                withDB(_.conversations.transaction(_.get(convId))).flatMap {
                  case None => Task.unit
                  case Some(conv) =>
                    conv.topics.headOption match {
                      case None => Task.unit
                      case Some(topic) =>
                        publish(sigil.event.ConversationHealed(
                          conversationId = convId,
                          topicId = topic.id,
                          correlationId = correlation,
                          strategyName = strategy.name,
                          corrections = result.corrections,
                          remainingIssues = result.remainingIssues,
                          participantId = agent.id
                        )).map(_ => ())
                    }
                }.handleError(_ => Task.unit)
              // Build the retry — same iteration recurse so the
              // healed-history check runs fresh. We deliberately
              // re-use the SAME claimed (the claim hasn't released);
              // iteration count keeps advancing.
              val retryTask: Task[Unit] = runAgentLoop(
                agent = agent,
                convId = convId,
                claimed = claimed,
                iteration = 1,
                sinceTimestamp = claimed.timestamp,
                greeting = false,
                userVisibleSeen = new java.util.concurrent.atomic.AtomicBoolean(false),
                turnExtractorFired = new java.util.concurrent.atomic.AtomicBoolean(false),
                failurePublished = new java.util.concurrent.atomic.AtomicBoolean(false),
                discoveredCapabilitiesRef = new AtomicReference(Map.empty),
                toolResultCacheRef = new AtomicReference(Map.empty),
                healedThisTurn = healedThisTurn,
                healCorrelationId = healCorrelationId
              )
              val pipeline: Task[Option[Task[Unit]]] = for {
                _ <- publishDetected
                result <- runStrategy
                out <- if (evidence.nonEmpty && result.corrections.isEmpty) {
                  // Sigil #314 — no-op heal: the strategy
                  // matched and ran but settled NOTHING
                  // against non-empty corruption evidence.
                  // Retrying would replay the identical
                  // broken frames and exhaust on the second
                  // pass — a heal masquerading as a fix.
                  // Don't publish `ConversationHealed`;
                  // mark the turn terminally exhausted, fire
                  // a `Failed` notice, give back the turn's
                  // allowance, and fall through to the
                  // standard failure path so the user sees a
                  // Failure bubble instead of a silent stall.
                  scribe.error(
                    s"heal[${strategy.name}] resolved ZERO corrections against ${evidence.size} " +
                      s"corruption row(s) for ${agent.id.value}/${convId.value} — treating as a failed " +
                      s"heal (no retry). remainingIssues=[${result.remainingIssues.mkString("; ")}]"
                  )
                  healedThisTurn.set(false)
                  publishExhausted
                    .flatMap(_ => notifyOutcome(sigil.heal.HealingOutcome.Failed))
                    .map(_ => Option.empty[Task[Unit]])
                } else {
                  publishHealed(result)
                    .flatMap(_ => notifyOutcome(sigil.heal.HealingOutcome.Healed))
                    .map(_ => Some(retryTask))
                }
              } yield out
              pipeline
            }
        }
    }
  }
}
