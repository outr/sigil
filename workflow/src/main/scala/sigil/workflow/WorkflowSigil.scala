package sigil.workflow

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB
import sigil.event.Event
import sigil.provider.Mode
import sigil.signal.Notice
import sigil.tool.Tool

import java.nio.file.Path

/**
 * Sigil refinement for apps that pull in `sigil-workflow`.
 *
 * Constrains `type DB` to a [[SigilDB]] subclass mixing in
 * [[WorkflowCollections]] (so `db.workflowTemplates` is reachable)
 * and provides the registration hooks for app-defined trigger /
 * step-input subtypes:
 *
 *   - [[workflowTriggerRegistrations]] — concrete
 *     [[WorkflowTrigger]] subtypes the app ships (Sage's Slack /
 *     Email / Git triggers, etc.). The four framework-shipped
 *     triggers (`ConversationMessageTrigger`, `TimeTrigger`,
 *     `WebhookTrigger`, `WorkflowEventTrigger`) are auto-registered.
 *
 *   - [[workflowStepInputRegistrations]] — extra [[WorkflowStepInput]]
 *     subtypes for app-specific composition shapes. The seven
 *     framework primitives are auto-registered.
 *
 * Sets [[WorkflowHost]] at trait init so compiled jobs / triggers
 * can reach back to this Sigil instance.
 *
 * Manages a Strider [[SigilWorkflowManager]] over its own LightDB
 * at `<workflowDbDirectory>` (default: a `workflows` sub-directory
 * of the host Sigil's `dbPath`). The manager publishes lifecycle
 * Events into the originating conversation when a run carries a
 * `conversationId` — same `Sigil.publish` path normal events flow
 * through.
 */
trait WorkflowSigil extends Sigil {
  type DB <: SigilDB & WorkflowCollections

  /** App-defined [[WorkflowTrigger]] subtypes — Sigil's framework
    * triggers (`ConversationMessageTrigger`, `TimeTrigger`,
    * `WebhookTrigger`, `WorkflowEventTrigger`) are auto-registered.
    * Apps add their own (Slack, GitHub, etc.) by overriding this. */
  protected def workflowTriggerRegistrations: List[RW[? <: WorkflowTrigger]] = Nil

  /** App-defined [[WorkflowStepInput]] subtypes. Sigil's seven
    * framework step shapes are auto-registered. */
  protected def workflowStepInputRegistrations: List[RW[? <: WorkflowStepInput]] = Nil

  /** Auto-register the workflow lifecycle Events so they round-trip
    * through fabric's polymorphic `Signal` discriminator. Apps that
    * override `eventRegistrations` should chain through `super`. */
  override protected def eventRegistrations: List[RW[? <: Event]] =
    summon[RW[sigil.workflow.event.WorkflowRunStarted]] ::
      summon[RW[sigil.workflow.event.WorkflowStepCompleted]] ::
      summon[RW[sigil.workflow.event.WorkflowRunCompleted]] ::
      summon[RW[sigil.workflow.event.WorkflowRunFailed]] ::
      super.eventRegistrations

  /** Auto-register the workflow Notices (approval prompts, etc.) so
    * the `Signal` poly RW round-trips them on the wire. */
  override protected def noticeRegistrations: List[RW[? <: Notice]] =
    summon[RW[sigil.workflow.signal.WorkflowApprovalRequested]] ::
      super.noticeRegistrations

  /** Set the process-wide [[WorkflowHost]] reference at trait init —
    * compiled jobs / triggers reach back to this Sigil through
    * that handle without threading it through Strider's engine. */
  WorkflowHost.set(this)

  /** Register the framework-shipped workflow triggers + step inputs
    * eagerly at trait init. These polys aren't reachable from the
    * aggregate `Signal` registry (workflows persist via Strider's
    * own collection), so they don't need to participate in
    * `polymorphicRegistrations`'s ordering — registering at trait
    * init is sufficient for the typed wire shapes to round-trip. */
  WorkflowTrigger.register((List(
    summon[RW[sigil.workflow.trigger.ConversationMessageTrigger]],
    summon[RW[sigil.workflow.trigger.TimeTrigger]],
    summon[RW[sigil.workflow.trigger.WebhookTrigger]],
    summon[RW[sigil.workflow.trigger.WorkflowEventTrigger]]
  ) ++ workflowTriggerRegistrations)*)

  WorkflowStepInput.register((List(
    summon[RW[JobStepInput]],
    summon[RW[ConditionStepInput]],
    summon[RW[ApprovalStepInput]],
    summon[RW[ParallelStepInput]],
    summon[RW[LoopStepInput]],
    summon[RW[SubWorkflowStepInput]],
    summon[RW[TriggerStepInput]]
  ) ++ workflowStepInputRegistrations)*)

  /** Override to point the Strider engine at a specific directory.
    * Default: a `workflows` sub-directory under the host Sigil's
    * `sigil.dbPath` (read via Profig — same source the framework
    * itself uses to locate its RocksDB / Lucene paths). Apps using
    * Postgres / non-disk storage override this to `None` and wire
    * a custom workflow DB. */
  protected def workflowDbDirectory: Option[Path] = {
    val raw = profig.Profig("sigil.dbPath").asOr[String]("db/sigil")
    Some(java.nio.file.Path.of(raw, "workflows"))
  }

  /** Maximum concurrent workflow runs the manager allows. */
  protected def maxConcurrentWorkflows: Int = 1

  /** The Strider DB this manager persists into. First access also
    * initializes the underlying LightDB — direct callers (the
    * scheduler, tests) can use the DB's `transaction` API safely
    * without separately invoking `workflowManager`. The lazy delay
    * ensures consumers can configure `sigil.dbPath` (via Profig /
    * `initFor`) before Strider reads it. */
  final lazy val workflowDb: SigilWorkflowDB = {
    val db = new SigilWorkflowDB(workflowDbDirectory)
    db.init.sync()
    db
  }

  /** The framework's workflow manager. Lazy-initialized on first
    * access — the engine starts when this is summoned. Runs until
    * [[Sigil.shutdown]] tears it down.
    *
    * Calls the manager's API directly to schedule / cancel /
    * resume runs from app code or tools. */
  final lazy val workflowManager: SigilWorkflowManager = {
    val manager = new SigilWorkflowManager(
      this.asInstanceOf[Sigil { type DB <: SigilDB & WorkflowCollections }],
      workflowDb,
      maxConcurrentWorkflows
    )
    manager.init().sync()
    _workflowManagerStarted = true
    manager
  }

  /** Whether the framework's workflow management tools (`create_workflow`,
    * `list_workflows`, `run_workflow`, …) are appended to `staticTools`.
    * Default true. Apps locking down the agent surface override to false
    * and register a curated subset. */
  def workflowToolsEnabled: Boolean = true

  /** Whether [[WorkflowBuilderMode]] is added to the registered
    * `modes`. Default true. */
  def workflowBuilderModeEnabled: Boolean = true

  override def staticTools: List[Tool] = {
    val base = super.staticTools
    if (workflowToolsEnabled) base ++ workflowManagementTools else base
  }

  protected def workflowManagementTools: List[Tool] = List(
    new sigil.workflow.tool.CreateWorkflowTool,
    new sigil.workflow.tool.UpdateWorkflowTool,
    new sigil.workflow.tool.DeleteWorkflowTool,
    new sigil.workflow.tool.ListWorkflowsTool,
    new sigil.workflow.tool.GetWorkflowTool,
    new sigil.workflow.tool.RunWorkflowTool,
    new sigil.workflow.tool.CancelWorkflowTool,
    new sigil.workflow.tool.ResumeWorkflowTool,
    new sigil.workflow.tool.RegisterTriggerTool,
    new sigil.workflow.tool.UnregisterTriggerTool,
    new sigil.workflow.tool.ListTriggersTool
  )

  override protected def modes: List[Mode] = {
    val base = super.modes
    if (workflowBuilderModeEnabled) WorkflowBuilderMode :: base else base
  }

  /** Tear down the Strider-backed workflow manager on Sigil shutdown.
    * Disposes the manager's executor fiber + flushes any in-flight
    * runs; chains through `super.onShutdown` so apps that mix multiple
    * modules into one Sigil tear each down in declaration order.
    *
    * Guarded by `_workflowManagerStarted` so we never accidentally
    * trigger `workflowManager`'s lazy init (which starts the engine)
    * just to dispose it — apps that never used the engine pay zero
    * shutdown cost. */
  override protected def onShutdown: rapid.Task[Unit] =
    (if (_workflowManagerStarted) workflowManager.dispose() else rapid.Task.unit)
      .flatMap(_ => super.onShutdown)

  /** Tracks whether `workflowManager`'s lazy val has been forced.
    * Updated by an internal accessor wrapper so `onShutdown` can
    * decide whether disposal is even needed. */
  @volatile private var _workflowManagerStarted: Boolean = false
}

