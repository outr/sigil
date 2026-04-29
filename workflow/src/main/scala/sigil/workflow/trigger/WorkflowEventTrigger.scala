package sigil.workflow.trigger

import fabric.{Json, obj, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.workflow.WorkflowTrigger
import strider.Workflow
import strider.step.{Step, Trigger, TriggerMode}

import java.util.concurrent.ConcurrentLinkedQueue

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

  /** Process-wide queue keyed by event name. Event publishers push
    * here; trigger `check` polls. Apps that want fan-out to multiple
    * waiting workflows use one trigger per consumer. */
  private val queues: java.util.concurrent.ConcurrentHashMap[String, ConcurrentLinkedQueue[Json]] =
    new java.util.concurrent.ConcurrentHashMap()

  private[workflow] def queueFor(name: String): ConcurrentLinkedQueue[Json] =
    queues.computeIfAbsent(name, _ => new ConcurrentLinkedQueue[Json]())

  private[workflow] def removeQueue(name: String): Unit = { queues.remove(name); () }

  /** Publish a named event; every waiting trigger with that name
    * sees the payload on its next `check`. */
  def publishEvent(name: String, payload: Json): Task[Unit] = Task {
    queueFor(name).add(payload); ()
  }
}

final case class WorkflowEventTriggerImpl(spec: WorkflowEventTrigger,
                                          id: Id[Step] = Step.id()) extends Trigger derives RW {
  override def name: String = "WorkflowEvent"
  override def mode: TriggerMode = TriggerMode.Continue

  override def register(workflow: Workflow): Task[Json] = Task {
    WorkflowEventTrigger.queueFor(spec.eventName)
    obj("eventName" -> str(spec.eventName), "registered" -> str("ok"))
  }

  override def check(workflow: Workflow): Task[Option[Json]] = Task {
    Option(WorkflowEventTrigger.queueFor(spec.eventName).poll())
  }

  override def unregister(workflow: Workflow): Task[Unit] = Task.unit
}
