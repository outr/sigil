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
 * Topic-stack cluster — the conversation's subject history and the
 * classifier that decides when the subject moved. Owns the settled
 * [[sigil.event.TopicChange]] projection onto `Conversation.topics`,
 * the [[sigil.tool.consult.TopicClassifierTool]] consult and its
 * failure notice, the shift resolution (`NoChange` / `Refine` /
 * `Return` / `New`) and the Topic records those verdicts write.
 *
 * Mixed into [[Sigil]]; the self-type reaches `withDB`, the consult
 * surface, and the notice channel.
 */
trait TopicOps { this: Sigil =>

  /** Update Conversation.topics in response to a settled TopicChange.
    *
    * For Switch: the change carries the post-transition topicId; we
    * either push a fresh entry or truncate the stack back to a matching
    * entry already present.
    *
    * For Rename: walk the stack and update label + summary on every
    * entry whose id matches the renamed topic (typically just the active
    * one). The Topic record itself is updated separately by the
    * orchestrator before publishing.
    */
  private[sigil] final def applyTopicChangeToStack(tc: TopicChange): Task[Unit] =
    withDB(_.conversations.transaction(_.modify(tc.conversationId) {
      case None => Task.pure(None)
      case Some(conv) =>
        tc.kind match {
          case TopicChangeKind.Switch(_) =>
            val existingIdx = conv.topics.indexWhere(_.id == tc.topicId)
            val nextStack: List[TopicEntry] =
              if (existingIdx >= 0) {
                // Truncate back to that entry — return to prior topic.
                conv.topics.take(existingIdx + 1)
              } else {
                // New topic — load the Topic record and push as a new entry.
                // Fall back to a stub if the record can't be resolved.
                conv.topics :+ TopicEntry(
                  id = tc.topicId,
                  label = tc.newLabel,
                  summary = ""  // populated below from the Topic record
                )
              }
            // If we appended a stub, fetch the Topic record to fill summary.
            val withSummary: Task[List[TopicEntry]] =
              if (existingIdx >= 0) Task.pure(nextStack)
              else withDB(_.topics.transaction(_.get(tc.topicId))).map {
                case Some(t) => nextStack.init :+ TopicEntry(t._id, t.label, t.summary)
                case None    => nextStack
              }
            withSummary.map { stack =>
              if (stack == conv.topics) Some(conv)
              else Some(conv.copy(topics = stack, modified = Timestamp(Nowish())))
            }
          case TopicChangeKind.Rename(_) =>
            // Refresh the entry whose id matches by reading the (already-renamed)
            // Topic record. Walk the stack and replace any matches.
            withDB(_.topics.transaction(_.get(tc.topicId))).map {
              case None => Some(conv)
              case Some(t) =>
                val updatedStack = conv.topics.map { e =>
                  if (e.id == tc.topicId) TopicEntry(t._id, t.label, t.summary) else e
                }
                if (updatedStack == conv.topics) Some(conv)
                else Some(conv.copy(topics = updatedStack, modified = Timestamp(Nowish())))
            }
        }
    })).unit

  // -- topic classification --

  /**
   * The framework's two-step topic resolver. Given the conversation's
   * current topic, its prior topics, and a proposed (label, summary)
   * from a respond call, ask the model to classify the relationship via
   * a focused [[TopicClassifierTool]] call (no conversation history,
   * just the inputs).
   *
   * Returns:
   *   - `NoChange` — same subject as Current; nothing to relabel.
   *   - `Refine`   — same subject as Current; adopt the sharper label.
   *   - `Return(prior)` — same subject as one of the priors; truncate
   *     the stack back to that entry.
   *   - `New`      — a brand new subject; push a fresh entry.
   *
   * If the classifier call fails (provider error, no tool call), falls
   * back to `NoChange` — the safe default that preserves state.
   */
  /** Topic labels the classifier should NEVER match against as
    * "<prior label>" — generic catch-alls (agent's own name, app
    * name, "Greeting", "Initial setup", "Chat", "Help") that
    * would otherwise pull every subsequent turn back to them via
    * the prior-match path. Apps that brand the agent override and
    * include the agent's display name. */
  def reservedTopicLabels: Set[String] = Set(
    "greeting", "initial setup", "chat", "help", "assistant", "conversation"
  )

  def classifyTopicShift(modelId: Id[Model],
                         chain: List[ParticipantId],
                         current: TopicEntry,
                         priors: List[TopicEntry],
                         proposedLabel: String,
                         proposedSummary: String,
                         userMessage: String,
                         // #357 — when supplied, the classifier consult routes via
                         // `auxModelFor` so an app that set `pinCoversAuxiliaryCalls`
                         // sends this aux call to the conversation's pinned model.
                         // `None` keeps the cost-first `routedModelFor` path.
                         conversationId: Option[Id[Conversation]] = None): Task[TopicShiftResult] = {
    // Bug #89 — strip reserved labels (agent name, "Greeting",
    // "Initial setup", etc.) from the prior list before the
    // classifier sees them. The `priors` parameter is the
    // conversation's persisted topic history (orchestrator passes
    // `request.previousTopics`). Filtering at the classifier
    // boundary stops an early seed topic that happens to be the
    // agent's own name from pulling every subsequent turn back to
    // it via "<prior label>" matching.
    val reservedLowered = reservedTopicLabels.map(_.toLowerCase)
    val filteredPriors = priors.filterNot(p => reservedLowered.contains(p.label.toLowerCase))
    // Bug #92 — also redact reserved-label substrings from the user
    // message before the classifier sees it. Anthropic + similar
    // providers don't grammar-constrain tool args, so the model can
    // hallucinate a `kind` straight from the user text it just read
    // ("Hi Sage" → kind="Sage"). Replacing the substrings shields
    // the classifier from echoing agent-name leakage as a topic
    // verdict. Match is case-insensitive, whole-word.
    val sanitizedUserMessage = reservedTopicLabels.foldLeft(userMessage) { (acc, term) =>
      acc.replaceAll(s"(?i)\\b${java.util.regex.Pattern.quote(term)}\\b", "[reserved]")
    }
    val priorsBlock =
      if (filteredPriors.isEmpty) "  (none)"
      else filteredPriors.map(p => s"  - \"${p.label}\" — ${p.summary}").mkString("\n")
    // A reserved / default-seed current label is a conversation-opening
    // placeholder, not established subject matter. Telling the
    // classifier so biases the first concrete label toward Refine
    // (relabel the placeholder in place) instead of New (mint a second
    // topic) — "greeting" → "actual subject" is the conversation
    // finding its subject, not a subject change.
    val currentIsPlaceholder =
      reservedLowered.contains(current.label.toLowerCase) ||
        current.label.equalsIgnoreCase(Topic.DefaultLabel)
    val placeholderNote =
      if (currentIsPlaceholder)
        "\n\nNote: the Current topic is a conversation-opening placeholder, not established subject matter. " +
          "If the proposed topic is the conversation's first concrete subject, answer \"Refine\" — " +
          "reserve \"New\" for a shift away from established work."
      else ""
    val systemPrompt =
      """You categorize how a proposed topic relates to a conversation's existing topics.
        |Pick exactly one value from the enum:
        |  - "NoChange" — proposed is the same subject as Current; nothing new to label.
        |  - "Refine"   — same subject as Current, but proposed is a sharper / more specific label.
        |  - <prior label> — same subject as one of the prior topics. The user is returning.
        |  - "New"      — genuinely different from Current and all priors.""".stripMargin
    val userPrompt =
      s"""User just said: ${quote(sanitizedUserMessage)}
         |
         |Current topic:
         |  - "${current.label}" — ${current.summary}
         |
         |Previous topics:
         |$priorsBlock
         |
         |Proposed topic for this turn:
         |  - "$proposedLabel" — $proposedSummary
         |
         |Pick exactly one value from the enum.$placeholderNote""".stripMargin
    val tool = new TopicClassifierTool(filteredPriors.map(_.label))
    // Sampling settings are baseline `temperature = 0.0` (deterministic
    // classification) — but only when the model supports it. GPT-5 +
    // reasoning-only families (o1, o3, …) hard-reject `temperature`,
    // so consult [[supportsParameter]] before including it. The
    // provider layer also filters as a safety net; gating here too
    // means the framework doesn't emit a parameter it knows the
    // model will reject.
    val started = System.currentTimeMillis()
    // Classifier consults route through the tool's declared work type
    // to the cheap classification tier and use its canonical
    // consultSettings (bounded output + reasoning off); temperature is
    // stamped when the routed model accepts it for deterministic
    // classification.
    val resolveClassifierModel = conversationId match {
      case Some(cid) => auxModelFor(cid, tool.consultWorkType, chain, modelId)
      case None      => routedModelFor(tool.consultWorkType, chain, modelId)
    }
    resolveClassifierModel.flatMap { routedModelId =>
      val classifierSettings = {
        val base = ConsultTool.settingsFor(tool)
        if (supportsParameter(routedModelId, "temperature")) base.copy(temperature = Some(0.0))
        else base
      }
      ConsultTool.invokeRich[sigil.tool.consult.TopicClassifierInput](
        sigil = this,
        modelId = routedModelId,
        chain = chain,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        tool = tool,
        generationSettings = classifierSettings
      )
    }.flatMap {
      case sigil.tool.consult.ConsultOutcome.Parsed(input) => Task.pure(input.kind match {
        case "NoChange" => TopicShiftResult.NoChange
        case "Refine"   => TopicShiftResult.Refine
        case "New"      => TopicShiftResult.New
        case other      =>
          // Bug #92 — defensive validator. If the classifier returns
          // a reserved label (agent name etc.) it must NOT be a Return
          // target even when it accidentally matches a persisted prior;
          // force `New` and log so future drift is observable.
          if (reservedLowered.contains(other.toLowerCase)) {
            scribe.warn(s"classifyTopicShift: model returned reserved label '$other' as kind — forcing New")
            TopicShiftResult.New
          } else {
            // Bug #89 — Return must hit a prior that survived the
            // reserved-label filter. If somehow the classifier
            // returned a label that was filtered out (or never
            // existed), fall back to "New" rather than NoChange so
            // the new topic actually gets recorded.
            filteredPriors.find(_.label == other)
              .map(TopicShiftResult.Return(_))
              .getOrElse {
                scribe.warn(s"classifyTopicShift: out-of-enum kind '$other' (priors=${filteredPriors.map(_.label).mkString(",")}) — falling back to New")
                TopicShiftResult.New
              }
          }
      })
      // `NoOpinion` is a legitimate "model declined to call the tool";
      // default to NoChange silently. `Truncated` / `Failed` are
      // diagnostic events — surface a Failed FrameworkWorkflowNotice
      // so the gap is visible to operators and downstream code
      // rather than being a zero-event silence.
      case sigil.tool.consult.ConsultOutcome.NoOpinion =>
        Task.pure(TopicShiftResult.NoChange)
      case t @ sigil.tool.consult.ConsultOutcome.Truncated(_, _, _) =>
        emitClassifierFailedNotice(
          "classifyTopicShift",
          s"truncated at finish_reason: length (promptTokens=${t.promptTokens.getOrElse("?")}, " +
            s"completionTokens=${t.completionTokens.getOrElse("?")}) — falling back to NoChange",
          started
        ).map(_ => TopicShiftResult.NoChange)
      case sigil.tool.consult.ConsultOutcome.Failed(cause) =>
        emitClassifierFailedNotice(
          "classifyTopicShift",
          s"${cause.getClass.getSimpleName}: ${Option(cause.getMessage).getOrElse("")} — falling back to NoChange",
          started
        ).map(_ => TopicShiftResult.NoChange)
      case sigil.tool.consult.ConsultOutcome.Unparseable(error) =>
        emitClassifierFailedNotice(
          "classifyTopicShift",
          s"classifier reply failed to decode (${error.render}) — falling back to NoChange",
          started
        ).map(_ => TopicShiftResult.NoChange)
    }
  }

  /** Surface a `ConsultOutcome.Truncated` / `Failed` from a framework-
    * internal classifier consult as a [[sigil.signal.FrameworkWorkflowNotice]]
    * with `Failed` phase. Wire subscribers (clients, dashboards) see
    * the gap; operator logs get a scribe.warn with the same reason. */
  private final def emitClassifierFailedNotice(workflowType: String,
                                               reason: String,
                                               startedMs: Long): Task[Unit] = {
    scribe.warn(s"$workflowType $reason")
    publish(sigil.signal.FrameworkWorkflowNotice(
      workflowId    = java.util.UUID.randomUUID().toString,
      workflowType  = workflowType,
      phase         = sigil.signal.FrameworkWorkflowPhase.Failed(
        reason     = reason,
        durationMs = System.currentTimeMillis() - startedMs
      )
    )).handleError(_ => Task.unit)
  }

  /**
   * Resolve a respond's declared `topicLabel` + `topicSummary` against
   * the conversation's topic stack. Returns the [[TopicChange]]
   * event(s) the caller should emit ahead of the respond's
   * [[Message]] — empty when the proposed topic is the active one
   * (no-op shift).
   *
   * Shared by both code paths that handle respond emission:
   *
   *   - [[sigil.orchestrator.Orchestrator]]'s streaming branch (when
   *     respond's content streamed live via ContentBlockDeltas — the
   *     orchestrator wraps the result as `Signal` and emits before
   *     the Message-settle delta).
   *   - [[sigil.tool.core.RespondTool.executeResult]] for atomic
   *     respond calls (llama.cpp grammar-constrained, OpenAI strict-
   *     mode, Anthropic, Google — every provider whose respond
   *     materialises as a function call). The tool's stream emits the
   *     TopicChange events as ordinary `Event`s; the orchestrator's
   *     `runExecute` pairs each with a settling `StateDelta`.
   *
   * Side effect: when the classifier returns `Refine`, the active
   * Topic record's label/summary is rewritten in-place; on `New`, a
   * fresh Topic record is persisted. Both paths emit the matching
   * `TopicChange` event.
   *
   * Fast-path shortcuts avoid the classifier LLM call when the
   * answer is unambiguous from a label match alone (active topic's
   * label, or any prior topic's label).
   */
  def resolveTopicShift(proposedLabel: String,
                        proposedSummary: String,
                        caller: ParticipantId,
                        conversation: Conversation,
                        currentTopic: TopicEntry,
                        previousTopics: List[TopicEntry],
                        modelId: Id[Model],
                        chain: List[ParticipantId],
                        userMessage: String): Task[List[Event]] = {
    if (proposedLabel.equalsIgnoreCase(currentTopic.label)) Task.pure(Nil)
    else previousTopics.find(_.label.equalsIgnoreCase(proposedLabel)) match {
      case Some(prior) =>
        Task.pure(List(buildSwitch(caller, conversation._id, currentTopic.id, prior.id, prior.label, prior.summary)))
      case None if conversation.topics.sizeIs == 1 && userMessage.trim.isEmpty =>
        // A label proposed before any user input reaches the context
        // (greeting turns, agent-initiated openers) is relabeling the
        // seed topic, not opening a second subject — the seed is a
        // placeholder and there is no established work to shift away
        // from. Adopt the proposal as a rename without consulting the
        // classifier; a `labelLocked` seed is respected (no-op) by
        // resolveRenameTopic.
        resolveRenameTopic(proposedLabel, proposedSummary, caller, conversation, currentTopic.id)
      case None =>
        classifyTopicShift(modelId, chain, currentTopic, previousTopics, proposedLabel, proposedSummary, userMessage,
                           conversationId = Some(conversation._id)).flatMap {
          case TopicShiftResult.NoChange       => Task.pure(Nil)
          case TopicShiftResult.Refine         => resolveRenameTopic(proposedLabel, proposedSummary, caller, conversation, currentTopic.id)
          case TopicShiftResult.New            => resolveNewTopic(proposedLabel, proposedSummary, caller, conversation, currentTopic.id)
          case TopicShiftResult.Return(prior)  =>
            Task.pure(List(buildSwitch(caller, conversation._id, currentTopic.id, prior.id, prior.label, prior.summary)))
        }
    }
  }

  private def buildSwitch(caller: ParticipantId,
                          convId: Id[Conversation],
                          previousTopicId: Id[Topic],
                          newTopicId: Id[Topic],
                          newLabel: String,
                          newSummary: String): TopicChange =
    TopicChange(
      kind           = TopicChangeKind.Switch(previousTopicId = previousTopicId),
      newLabel       = newLabel,
      newSummary     = newSummary,
      participantId  = caller,
      conversationId = convId,
      topicId        = newTopicId
    )

  private def resolveNewTopic(proposedLabel: String,
                              proposedSummary: String,
                              caller: ParticipantId,
                              conversation: Conversation,
                              previousTopicId: Id[Topic]): Task[List[Event]] = {
    val created = Topic(
      conversationId = conversation._id,
      label          = proposedLabel,
      summary        = proposedSummary,
      createdBy      = caller
    )
    withDB(_.topics.transaction(_.upsert(created))).map { stored =>
      List(buildSwitch(caller, conversation._id, previousTopicId, stored._id, stored.label, stored.summary))
    }
  }

  private def resolveRenameTopic(proposedLabel: String,
                                 proposedSummary: String,
                                 caller: ParticipantId,
                                 conversation: Conversation,
                                 currentTopicId: Id[Topic]): Task[List[Event]] =
    withDB(_.topics.transaction(_.get(currentTopicId))).flatMap {
      case None                                  => Task.pure(Nil)
      case Some(current) if current.labelLocked  => Task.pure(Nil)
      case Some(current)                         =>
        val renamed = current.copy(label = proposedLabel, summary = proposedSummary, modified = Timestamp())
        withDB(_.topics.transaction(_.upsert(renamed))).map { _ =>
          List(TopicChange(
            kind           = TopicChangeKind.Rename(previousLabel = current.label),
            newLabel       = proposedLabel,
            newSummary     = proposedSummary,
            participantId  = caller,
            conversationId = conversation._id,
            topicId        = current._id
          ))
        }
    }

  /** Persist the agent's per-turn keyword push (from `RespondInput.keywords`)
    * onto the conversation as `currentKeywords`. The non-critical memory
    * retriever reads this on the next turn — no event is emitted because
    * the keywords are turn-state, not durable history. Empty input is a
    * no-op so the agent isn't forced to push a list it doesn't have.
    *
    * Called from both [[sigil.tool.core.RespondTool]] and
    * [[sigil.orchestrator.Orchestrator]]'s streaming-respond branch so
    * the keyword side effect fires regardless of which respond path
    * materialised. */
  def updateConversationKeywords(conversationId: Id[Conversation],
                                 keywords: List[String]): Task[Unit] = {
    val cleaned = keywords.iterator.map(_.trim).filter(_.nonEmpty).toVector.distinct
    if (cleaned.isEmpty) Task.unit
    else withDB(_.conversations.transaction(_.modify(conversationId) {
      case Some(c) => Task.pure(Some(c.copy(currentKeywords = cleaned, modified = Timestamp())))
      case None    => Task.pure(None)
    })).unit
  }

  private def quote(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""
}
