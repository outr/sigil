package sigil.workflow.trigger

import fabric.{Json, obj, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.workflow.WorkflowTrigger
import strider.Workflow
import strider.step.{Step, Trigger, TriggerMode}

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}

/**
 * Workflow trigger that fires when another workflow publishes a
 * named event via [[publishEvent]]. The Sigil-side primitive for
 * cross-workflow signaling — workflow A finishes a step, calls
 * `publishEvent("foo", payload)`, workflow B paused on
 * `WorkflowEventTrigger("foo")` resumes with that payload.
 *
 * `eventName` is the bus key. Apps that want filter semantics
 * (e.g. only events whose payload matches a predicate) build a
 * thin condition step downstream of the trigger.
 */
final case class WorkflowEventTrigger(eventName: String)
  extends WorkflowTrigger derives RW {

  override def kind: String = WorkflowEventTrigger.Kind

  override def compile(host: Sigil): Trigger = WorkflowEventTriggerImpl(this)
}

object WorkflowEventTrigger {
  val Kind: String = "workflow_event"

  /** Per-event-name state: the message queue plus a refcount of
    * registered triggers waiting on it. The refcount lets
    * `unregister` drop the queue when the last trigger detaches —
    * apps that generate dynamic event names (per-task ids, etc.)
    * don't accumulate orphan queues in the static map. */
  private final case class QueueState(queue: ConcurrentLinkedQueue[Json], refCount: Int)

  private val queues: ConcurrentHashMap[String, QueueState] = new ConcurrentHashMap()

  /** Acquire the queue for `name`, bumping the refcount so a
    * concurrent `release` won't drop it from under us. */
  private[workflow] def acquire(name: String): ConcurrentLinkedQueue[Json] =
    queues.compute(name, (_, existing) => {
      if (existing == null) QueueState(new ConcurrentLinkedQueue[Json](), 1)
      else existing.copy(refCount = existing.refCount + 1)
    }).queue

  /** Release one reference to the queue for `name`. When the
    * refcount drops to zero the queue is removed from the static
    * map; remaining buffered payloads are discarded (no listener
    * means no consumer to deliver to). */
  private[workflow] def release(name: String): Unit = {
    queues.compute(name, (_, existing) => {
      if (existing == null) null
      else if (existing.refCount <= 1) null
      else existing.copy(refCount = existing.refCount - 1)
    })
    ()
  }

  /** Look up an existing queue without acquiring a reference. Used
    * by [[publishEvent]]: if no trigger is registered for `name`,
    * publishing is a no-op (no consumer to deliver to, no orphan
    * queue to leak). */
  private[workflow] def queueFor(name: String): Option[ConcurrentLinkedQueue[Json]] =
    Option(queues.get(name)).map(_.queue)

  /** Diagnostic — returns true while at least one trigger is
    * registered for `name`. Useful for tests and for apps that want
    * to surface "is anyone listening for this event?" before
    * publishing expensive payloads. */
  def isRegistered(name: String): Boolean = queues.containsKey(name)

  /** Publish a named event; every waiting trigger with that name
    * sees the payload on its next `check`. Returns immediately if
    * no trigger is currently registered for `name` — keeps the
    * static queue map bounded by *active* listener cardinality
    * rather than ever-published cardinality. */
  def publishEvent(name: String, payload: Json): Task[Unit] = Task {
    queueFor(name).foreach(_.add(payload))
  }
}

final case class WorkflowEventTriggerImpl(spec: WorkflowEventTrigger,
                                          id: Id[Step] = Step.id()) extends Trigger derives RW {
  override def name: String = "WorkflowEvent"
  override def mode: TriggerMode = TriggerMode.Continue

  override def register(workflow: Workflow): Task[Json] = Task {
    WorkflowEventTrigger.acquire(spec.eventName)
    obj("eventName" -> str(spec.eventName), "registered" -> str("ok"))
  }

  override def check(workflow: Workflow): Task[Option[Json]] = Task {
    WorkflowEventTrigger.queueFor(spec.eventName).flatMap(q => Option(q.poll()))
  }

  override def unregister(workflow: Workflow): Task[Unit] = Task {
    WorkflowEventTrigger.release(spec.eventName)
  }
}
