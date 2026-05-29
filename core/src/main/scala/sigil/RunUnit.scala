package sigil

import lightdb.id.Id
import rapid.Task
import sigil.conversation.Conversation
import sigil.signal.{FrameworkWorkflowNotice, FrameworkWorkflowPhase}

/**
 * A self-describing unit of framework work whose lifecycle is observable
 * through the [[sigil.signal.FrameworkWorkflowNotice]] channel.
 *
 * Replaces the per-callsite `try/catch + emit Started/Failed/Completed`
 * boilerplate that previously lived inline at each framework-internal
 * surface. A `RunUnit` declares its own metadata (label, workflow type,
 * conversation scope, optional parent-conversation hint) plus the
 * Task to execute, and [[RunUnit.execute]] handles the observability
 * wrap uniformly. Apps wrap their own framework-internal operations by
 * defining a `RunUnit` — the framework treats `workflowType` as opaque.
 *
 * The terminal hooks ([[onCompleted]], [[onFailed]]) are
 * side-effect / observation surfaces, not recovery surfaces — failures
 * are always re-thrown by [[RunUnit.execute]] regardless of what
 * `onFailed` does. Callers that need to absorb a failure wrap the
 * `execute` call in `.handleError(...)` themselves.
 *
 * `parentConversationId` is a forward-compat hint carried for future
 * cross-conversation echo wiring; today's `execute` does NOT auto-publish
 * any cross-conversation event. The `delegate_task` parent-echo flow
 * continues to be sourced from
 * [[sigil.workflow.SigilWorkflowManager.maybePublishTaskExecuted]].
 */
trait RunUnit[T] {

  /** User-facing label for the operation — appears verbatim in the
    * `Started` Notice. Short, action-shaped ("rendering pre-flight",
    * "compressing context", "agent loop"). */
  def label: String

  /** Coarse-grained category for client-side filtering / grouping.
    * Opaque to the framework. Existing categories: `"preflight"`,
    * `"compress"`, `"agent-loop"`. Apps add their own. */
  def workflowType: String

  /** Conversation scope when applicable. `None` is the rare
    * cross-conversation case (e.g. a global maintenance task). Used by
    * client UIs to scope rendering to the active conversation. */
  def conversationId: Option[Id[Conversation]]

  /** Forward-compat hint at the parent conversation when the unit is
    * spawned by another (worker delegation, sub-conversation tooling).
    * [[RunUnit.execute]] does NOT publish cross-conversation echoes —
    * that stays the responsibility of the surface emitting the
    * persistent Event (e.g.
    * [[sigil.workflow.SigilWorkflowManager.maybePublishTaskExecuted]]).
    * Defined here so a future operational cross-conversation Notice
    * (if/when justified) finds the metadata already plumbed. */
  def parentConversationId: Option[Id[Conversation]] = None

  /** The work to perform. Whatever this Task produces flows through
    * [[onCompleted]] on success; whatever it raises flows through
    * [[onFailed]] before being re-thrown. */
  def run: Task[T]

  /** Observation hook fired on successful completion. Default no-op.
    * Apps override to fold the result into custom telemetry / hand
    * to a downstream effect / wire to a custom Notice channel. NOT
    * a recovery hook — see [[RunUnit.execute]] for the failure
    * contract. */
  def onCompleted(result: T): Task[Unit] = Task.unit

  /** Observation hook fired on failure. Default no-op. Apps override
    * to capture error telemetry / wire a custom Notice / record a
    * trace. NOT a recovery hook — the failure is re-thrown by
    * [[RunUnit.execute]] regardless of what this returns. */
  def onFailed(t: Throwable): Task[Unit] = Task.unit

  /** Whether [[RunUnit.execute]] should publish a `Started` Notice on
    * entry. Default `true`. Callsites that already emit a persistent
    * `Started` Event (e.g. workflow runs with Strider's
    * `onWorkflowStarted` hook) and don't need a parallel operational
    * pulse override to `false`. Suppressing this does NOT silence the
    * terminal Notice — `Completed` / `Failed` still fire. */
  def emitStarted: Boolean = true

  /** Whether [[RunUnit.execute]] should publish a `Completed` Notice
    * on success. Default `true`. The agent-loop wrap sets this to
    * `false` because the loop already publishes a richer settled-state
    * signal (the `AgentStateDelta` carrying `Idle` / `Complete`) and
    * a duplicate operational `Completed` would only add noise. The
    * `Failed` Notice still fires when relevant; observers always have
    * a terminal pulse to act on. */
  def emitCompleted: Boolean = true

  /** Stable identifier for this unit's pulses. Every Notice emitted by
    * [[RunUnit.execute]] for this unit carries this id, so client UIs
    * can correlate `Started` / `Step` / `Completed` / `Failed` to one
    * concrete run. Defaults to a freshly-generated UUID computed
    * once per unit instance via the [[freshWorkflowId]] helper.
    * Adapters that share a single id with an external surface
    * (e.g. the legacy [[FrameworkWorkflowControl]] step-callback path
    * needs the same id on its Step pulses as on the outer Started /
    * Failed) override this to keep IDs aligned. */
  lazy val workflowId: String = RunUnit.freshWorkflowId()
}

object RunUnit {

  /** Default workflow-id factory. Exposed so the existing
    * [[Sigil.runAsFrameworkWorkflow]] path can hand its
    * [[CancellationToken]] a matching id (the token's workflowId is
    * what `cancel_framework_workflow` keys on). */
  def freshWorkflowId(): String = java.util.UUID.randomUUID().toString

  /**
   * Run a [[RunUnit]] with lifecycle observability.
   *
   * The Notice stream is:
   *   1. `Started(label)` — when `unit.emitStarted` (default true).
   *   2. `Completed(durationMs)` on success when `unit.emitCompleted`
   *      (default true). Always fires `unit.onCompleted(value)` before
   *      the Notice.
   *   3. `Failed(reason, durationMs)` on failure (unconditional —
   *      observers always get a terminal pulse). Always fires
   *      `unit.onFailed(throwable)` before the Notice, and the
   *      throwable is re-thrown after.
   *
   * Failures are ALWAYS re-thrown. `onFailed` is an observation
   * hook, not a recovery hook. Callers that want to absorb a failure
   * call `.handleError(...)` on `RunUnit.execute(...)`.
   *
   * **Complementary, not duplicative** with persistent lifecycle
   * Events. Some callsites (workflow runs) have Strider's `onWorkflow*`
   * hooks emit durable `WorkflowRunStarted` / `WorkflowRunFailed` /
   * `WorkflowRunCompleted` Events AND wrap the workflow body in
   * `RunUnit.execute` for operational visibility. The two layers are
   * intentionally distinguishable by signal type — Events are
   * persistent and replayable for durable lifecycle history, Notices
   * are transient and serve operational / observability consumers
   * (activity bars, latency traces). Do NOT collapse them; each layer
   * has different consumers and survives different failure modes
   * (Strider's hooks fire even if the body never reaches `RunUnit`,
   * preserving lifecycle coverage when init throws before the wrap
   * activates — see `WorkflowInitFailureLifecycleSpec`).
   */
  def execute[T](unit: RunUnit[T])(using sigil: Sigil): Task[T] = {
    val workflowId = unit.workflowId
    val started    = System.currentTimeMillis()
    def elapsed: Long = System.currentTimeMillis() - started
    def emit(phase: FrameworkWorkflowPhase): Task[Unit] =
      sigil.publish(FrameworkWorkflowNotice(workflowId, unit.workflowType, phase, unit.conversationId))
    val startedEmit: Task[Unit] =
      if (unit.emitStarted) emit(FrameworkWorkflowPhase.Started(unit.label))
      else Task.unit
    startedEmit.flatMap { _ =>
      unit.run.attempt.flatMap { result =>
        result match {
          case scala.util.Success(value) =>
            unit.onCompleted(value).handleError(t =>
              Task(scribe.warn(s"RunUnit.onCompleted handler raised on $workflowId: ${t.getMessage}"))
            ).flatMap { _ =>
              val completedEmit: Task[Unit] =
                if (unit.emitCompleted) emit(FrameworkWorkflowPhase.Completed(elapsed))
                else Task.unit
              completedEmit.map(_ => value)
            }
          case scala.util.Failure(err) =>
            val reason = s"${err.getClass.getSimpleName}: ${Option(err.getMessage).getOrElse("")}"
            unit.onFailed(err).handleError(t =>
              Task(scribe.warn(s"RunUnit.onFailed handler raised on $workflowId: ${t.getMessage}"))
            ).flatMap { _ =>
              emit(FrameworkWorkflowPhase.Failed(reason, elapsed))
                .flatMap(_ => Task.error(err))
            }
        }
      }
    }
  }
}
