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
 * Polymorphic-registration cluster — phase-1 of the lifecycle. Owns
 * the derived RW rosters and subtype-name sets the wire routers and
 * codegen read, the memoized static-tool roster, and
 * [[Sigil.polymorphicRegistrations]] itself: the ordered population of
 * every fabric `PolyType` discriminator plus the boot-completeness
 * check that runs against the resolved roster.
 *
 * Mixed into [[Sigil]]; the self-type reaches the app-facing
 * `*Registrations` hooks, `staticTools`, `modes`, and `workTypes`.
 */
trait RegistrationOps { this: Sigil =>

  /** Every Event RW the framework knows about — `CoreSignals.events ++ eventRegistrations`. */
  final def allEventRWs: List[RW[? <: Event]] = CoreSignals.events ++ eventRegistrations

  /** Every Delta RW the framework knows about — `CoreSignals.deltas ++ deltaRegistrations`. */
  final def allDeltaRWs: List[RW[? <: Delta]] = CoreSignals.deltas ++ deltaRegistrations

  /** Every Notice RW the framework knows about — `CoreSignals.notices ++ noticeRegistrations`. */
  final def allNoticeRWs: List[RW[? <: Notice]] = CoreSignals.notices ++ noticeRegistrations

  /**
   * Simple-class-name set of every registered Event subtype — what wire
   * routers / Dart codegen / spice's `durableSubtypes` knob need to
   * distinguish "persist + replay this subtype" from "transient pulse".
   *
   * Names match the wire discriminator that fabric writes for each subtype
   * (`Product.productPrefix` — i.e. the simple class name). Apps that add
   * custom Events via `eventRegistrations` see them surface here
   * automatically.
   */
  final def eventSubtypeNames: Set[String] =
    allEventRWs.flatMap(_.definition.className).map(simpleClassName).toSet

  /** Simple-class-name set of every registered Delta subtype. */
  final def deltaSubtypeNames: Set[String] =
    allDeltaRWs.flatMap(_.definition.className).map(simpleClassName).toSet

  /** Simple-class-name set of every registered Notice subtype. */
  final def noticeSubtypeNames: Set[String] =
    allNoticeRWs.flatMap(_.definition.className).map(simpleClassName).toSet

  private def simpleClassName(fullName: String): String = {
    val lastDot = fullName.lastIndexOf('.')
    val lastDollar = fullName.lastIndexOf('$')
    val start = math.max(lastDot, lastDollar) + 1
    fullName.substring(start)
  }

  private val staticToolsMemo =
    new java.util.concurrent.atomic.AtomicReference[Option[List[sigil.tool.Tool]]](None)

  /** Memoized first read of [[staticTools]] — the framework's single
    * access path to the static roster. */
  final def resolvedStaticTools: List[sigil.tool.Tool] = staticToolsMemo.get() match {
    case Some(list) => list
    case None =>
      val fresh = staticTools
      if (staticToolsMemo.compareAndSet(None, Some(fresh))) fresh
      else staticToolsMemo.get().get
  }

  /**
   * Phase-1 lifecycle: populate every fabric `PolyType` discriminator
   * with the framework + app-defined subtypes. Pure JVM-level effect
   * — does not open the LightDB / RocksDB store, does not start any
   * background fibers. Idempotent (`.singleton`).
   *
   * Codegen / schema-introspection tasks (e.g. Dart generator,
   * OpenAPI schema dumper) call `polymorphicRegistrations.sync()`
   * instead of `instance.sync()`. That gives them the populated
   * `summon[RW[Signal]].definition`, `summon[RW[ParticipantId]].definition`,
   * etc. without contending with a live backend for the RocksDB
   * lock — multiple developer terminals can run codegen against the
   * same Sigil module while a server is running.
   *
   * `instance` runs this first, so runtime consumers see the same
   * ordering as before.
   *
   * **Registration order matters.** Leaf polys (no fields referencing
   * other polys) MUST be populated before composite polys whose
   * case-class subtypes have fields typed against the leaves —
   * otherwise fabric's lazy-val `Definition` for those subtypes
   * captures an empty leaf-poly snapshot when `RW.poly` walks them
   * at register-time, and downstream callers see leaf polys with
   * zero subtypes.
   */
  val polymorphicRegistrations: Task[Unit] = Task.defer {
    for {
      _ <- logger.info("Sigil registering polymorphic discriminators...")
      // Leaf polys (no fields referencing other polys) first — `RW.poly`
      // reads each subtype's `definition` eagerly at register-time
      // (fabric/rw/RW.scala:207) and case-class definitions are
      // `lazy val` (fabric/rw/CompileRW.scala:996), so the first read
      // freezes the leaf-poly state in. Any aggregate registration
      // (Participant, Tool, Signal) whose subtypes have fields typed
      // against a leaf must run after that leaf, otherwise downstream
      // consumers (notably the Spice Dart codegen) see empty
      // dispatchers despite the leaf register call succeeding.
      _ = SpaceId.register((RW.static(GlobalSpace) :: spaceIds).distinct*)
      _ = sigil.tool.ToolKind.register(
            (RW.static(sigil.tool.BuiltinKind) :: RW.static(sigil.tool.InternalKind) ::
              RW.static(sigil.tool.consult.ConsultKind) :: toolKindRegistrations).distinct*
          )
      _ = ParticipantId.register((summon[RW[sigil.participant.WorkerParticipantId]] :: participantIds).distinctBy(_.definition.className)*)
      _ = Mode.register((ConversationMode :: modes).distinct.map(m => RW.static(m))*)
      _ = sigil.provider.WorkType.register(workTypes.map(w => RW.static(w))*)
      // Sigil #386 — app-defined conversation status; framework `Open`
      // default auto-registered. Leaf poly (referenced by `Conversation.status`
      // and the `ConversationStatusChanged` notice), so registers here before
      // the aggregate Signal registration below walks the notice definitions.
      _ = sigil.conversation.ConversationStatus.register(
            (RW.static(sigil.conversation.ConversationStatus.Open) :: conversationStatusRegistrations).distinct*
          )
      // Mixin hook — runs AFTER the framework leaf polytypes (SpaceId,
      // ParticipantId, Mode, WorkType, …) register but BEFORE any aggregate
      // that walks tool / participant / signal Definitions. A mixin polytype
      // referenced by a tool input (e.g. a `WorkflowStepInput` in
      // `create_workflow`'s `steps` schema) MUST be registered before the
      // `ToolInput.register` below forces those input Definitions via
      // `.distinctBy(_.definition.className)` — accessing `.definition`
      // freezes the lazy-val snapshot, so a subtype registered afterward never
      // appears in the rendered schema (the field collapses to `array<string>`
      // and no agent can fill it). WorkflowSigil registers `WorkflowTrigger`
      // first, then `WorkflowStepInput` (which references it), so the leaf-poly
      // ordering #18 guards against is preserved here too.
      _ <- mixinPolymorphicRegistrations
      // Input AND output poly-RWs derive symmetrically from the same
      // sources: the memoized static roster's ToolIO, the finder's
      // declared IO contribution (an app override of `findTools`
      // contributes its codecs by construction instead of silently
      // disabling the static channel), and the explicit registration
      // lists for runtime-created records (`ScriptTool` / dynamic
      // tools whose classes have no static instance).
      registeredToolIO = resolvedStaticTools.map(_.io) ++ findTools.toolIO
      staticInputRWs = registeredToolIO.map(_.inputRW.asInstanceOf[RW[? <: sigil.tool.ToolInput]])
      _ = ToolInput.register((CoreTools.inputRWs ++ staticInputRWs ++ toolInputRegistrations).distinctBy(_.definition.className)*)
      // Sigil #265 — `ToolOutput` is a polymorphic discriminator on
      // `ToolInvoke.output`. Register the framework-shipped `Pending` /
      // `Progress` cases, every registered ToolIO's output RW, and any
      // app-defined output subtypes so `ToolInvoke` RW round-trips
      // cleanly through persistence and the wire.
      staticOutputRWs = registeredToolIO.map(_.outputRW.asInstanceOf[RW[? <: sigil.tool.ToolOutput]])
      // A tool whose `Output` is the open `ToolOutput` itself (e.g. an
      // MCP tool that returns text OR an image) carries the base
      // PolyType RW as its `outputRW`. Registering that base RW would
      // re-expand the whole hierarchy and collide every already-listed
      // leaf — so drop it here (no concrete leaf is named `ToolOutput`).
      baseToolOutputClassName = summon[RW[sigil.tool.ToolOutput]].definition.className
      _ = sigil.tool.ToolOutput.register(
            (sigil.tool.ToolOutput.frameworkOutputRWs ++ staticOutputRWs ++ toolOutputRegistrations)
              .filterNot(_.definition.className == baseToolOutputClassName)
              .distinctBy(_.definition.className)*
          )
      _ = sigil.viewer.ViewerStatePayload.register(viewerStatePayloadRegistrations.distinct*)
      // Heal-pipeline polytypes. The framework-shipped CorruptionEvidence
      // subtypes are registered here; apps with their own evidence
      // shapes register through `corruptionEvidenceRegistrations`.
      _ = sigil.heal.CorruptionEvidence.register(
            (List[RW[? <: sigil.heal.CorruptionEvidence]](
              summon[RW[sigil.heal.CorruptionEvidence.MissingToolResult]],
              summon[RW[sigil.heal.CorruptionEvidence.DanglingToolResultOrigin]],
              summon[RW[sigil.heal.CorruptionEvidence.OrphanSummaryCoverage]]
            ) ++ corruptionEvidenceRegistrations).distinct*
          )
      // Aggregates after leaves + mixins.
      _ = Participant.register((summon[RW[DefaultAgentParticipant]] :: participants)*)
      _ = sigil.tool.Tool.register((resolvedStaticTools.map(t => RW.static(t)) ++ toolRegistrations).distinct*)
      _ = sigil.skill.Skill.register((staticSkills.map(s => RW.static(s)) ++ skillRegistrations).distinct*)
      _ = Signal.register((allEventRWs ++ allDeltaRWs ++ allNoticeRWs ++ signalRegistrations)*)
      // Boot completeness — every registered tool's probe input/output
      // must round-trip through the polymorphic RWs, names must be
      // roster-wide unique, no two IO types may collide on their simple
      // class name, and suggested-next references must resolve. Needs
      // only the tool list + the registrations above, so codegen-only
      // flows (no DB) run it too.
      _ = sigil.tool.BootCompletenessCheck.run(resolvedStaticTools)
    } yield ()
  }.singleton
}
