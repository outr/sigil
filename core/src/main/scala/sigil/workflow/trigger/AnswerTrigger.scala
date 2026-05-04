package sigil.workflow.trigger

import fabric.{Json, obj, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.{FiberOps, Task}
import sigil.Sigil
import sigil.signal.WorkerAnswer
import sigil.workflow.{WorkflowHost, WorkflowTrigger}
import strider.Workflow
import strider.step.{Step, Trigger, TriggerMode}

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicBoolean
import scala.compiletime.uninitialized

/**
 * Workflow trigger that suspends a worker run until the parent
 * agent responds to a previously-asked question.
 *
 * Fires on a [[sigil.signal.WorkerAnswer]] Notice whose `taskId`
 * matches this trigger's run id and whose `questionId` matches the
 * question the worker asked. Both filters apply — workers can ask
 * several questions in flight and answers route to the right one
 * by `questionId`.
 */
final case class AnswerTrigger(taskId: String,
                               questionId: String) extends WorkflowTrigger derives RW {

  override def kind: String = AnswerTrigger.Kind

  override def compile(host: Sigil): Trigger = AnswerTriggerImpl(this)
}

object AnswerTrigger {
  val Kind: String = "answer"

  /** Per-(taskId, questionId) state: matched-answers queue plus a
    * refcount of registered triggers waiting on it. Lives in a
    * static map so state survives the trigger's per-poll
    * rehydration from the workflow row's persisted JSON — Strider's
    * `checkWaitingWorkflows` reads the workflow fresh each tick and
    * gets a brand-new `AnswerTriggerImpl` each time; without static
    * keyed storage the matched-queue would be reset between writes
    * and reads. Same shape as [[WorkflowEventTrigger]]'s queues. */
  private final case class QueueState(queue: ConcurrentLinkedQueue[WorkerAnswer], refCount: Int)

  private val queues: ConcurrentHashMap[(String, String), QueueState] = new ConcurrentHashMap()

  private[trigger] def acquire(taskId: String, questionId: String): ConcurrentLinkedQueue[WorkerAnswer] =
    queues.compute((taskId, questionId), (_, existing) => {
      if (existing == null) QueueState(new ConcurrentLinkedQueue[WorkerAnswer](), 1)
      else existing.copy(refCount = existing.refCount + 1)
    }).queue

  private[trigger] def release(taskId: String, questionId: String): Unit = {
    queues.compute((taskId, questionId), (_, existing) => {
      if (existing == null) null
      else if (existing.refCount <= 1) null
      else existing.copy(refCount = existing.refCount - 1)
    })
    ()
  }

  private[trigger] def queueFor(taskId: String, questionId: String): Option[ConcurrentLinkedQueue[WorkerAnswer]] =
    Option(queues.get((taskId, questionId))).map(_.queue)

  /** Diagnostic — true while at least one trigger is registered
    * for `(taskId, questionId)`. Tests use this to assert the
    * register / unregister lifecycle is balanced. */
  def isRegistered(taskId: String, questionId: String): Boolean =
    queues.containsKey((taskId, questionId))
}

/** Strider-side implementation. Matched-answers state is keyed off
  * the static [[AnswerTrigger.queues]] map so it survives the
  * trigger's per-poll rehydration from the workflow row's persisted
  * JSON. The fiber that subscribes to `sigil.signals` is started
  * fresh on each register; Strider only registers a trigger once
  * per workflow lifecycle so this isn't load-bearing across
  * rehydrations. */
final case class AnswerTriggerImpl(spec: AnswerTrigger,
                                   id: Id[Step] = Step.id())
  extends Trigger derives RW {

  override def name: String = "Answer"
  override def mode: TriggerMode = TriggerMode.Continue

  @transient @volatile private var fiber: rapid.Fiber[Unit] = uninitialized
  @transient private val running = new AtomicBoolean(false)

  override def register(workflow: Workflow): Task[Json] = Task {
    val sigil = WorkflowHost.get
    val q = AnswerTrigger.acquire(spec.taskId, spec.questionId)
    running.set(true)
    fiber = sigil.signals
      .takeWhile(_ => running.get())
      .collect { case w: WorkerAnswer => w }
      .filter(w => w.taskId == spec.taskId && w.questionId == spec.questionId)
      .evalMap(w => Task { q.add(w); () })
      .drain
      .start()
    obj("taskId" -> str(spec.taskId), "questionId" -> str(spec.questionId), "registered" -> str("ok"))
  }

  override def check(workflow: Workflow): Task[Option[Json]] = Task {
    AnswerTrigger.queueFor(spec.taskId, spec.questionId).flatMap(q => Option(q.poll())).map { w =>
      obj(
        "taskId"     -> str(w.taskId),
        "questionId" -> str(w.questionId),
        "answer"     -> str(w.answer)
      ): Json
    }
  }

  override def unregister(workflow: Workflow): Task[Unit] = Task {
    running.set(false)
    fiber = null
    AnswerTrigger.release(spec.taskId, spec.questionId)
  }
}
