package sigil.workflow.trigger

import fabric.{Json, Null, obj, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.workflow.WorkflowTrigger
import strider.Workflow
import strider.step.{Step, Trigger, TriggerMode}

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Workflow trigger that fires on inbound HTTP webhook calls. The
 * agent supplies a `path` (under `/workflow/webhook/`) and a
 * `secret` — incoming POSTs whose `X-Webhook-Secret` header
 * matches enqueue a payload that resumes the trigger.
 *
 * The framework's webhook listener is wired by [[WorkflowSigil]] —
 * a single HTTP listener multiplexes all registered triggers by
 * path. Apps that want a custom listener route override the
 * `webhookEndpointHandler` hook.
 *
 * `mode = "branch"` is recommended for "fire-and-forget on every
 * inbound POST" workflows.
 */
final case class WebhookTrigger(path: String,
                                secret: String)
  extends WorkflowTrigger derives RW {

  override def kind: String = WebhookTrigger.Kind

  override def compile(host: Sigil): Trigger = WebhookTriggerImpl(this)
}

object WebhookTrigger {
  val Kind: String = "webhook"

  /** Process-wide registry of active webhook trigger queues, keyed
    * by `path`. `WorkflowSigil`'s HTTP handler delivers payloads
    * here; trigger instances drain on `check`. */
  private val queues: java.util.concurrent.ConcurrentHashMap[String, ConcurrentLinkedQueue[Json]] =
    new java.util.concurrent.ConcurrentHashMap()

  /** Look up (or create) the queue for a given webhook path. The
    * webhook listener pushes payloads into the matching queue;
    * trigger `check` polls it. */
  def queueFor(path: String): ConcurrentLinkedQueue[Json] =
    queues.computeIfAbsent(path, _ => new ConcurrentLinkedQueue[Json]())

  /** Drop the queue for a path (called from `unregister`). */
  def removeQueue(path: String): Unit = { queues.remove(path); () }

  /** Snapshot of registered paths — used by the framework's
    * webhook handler to validate inbound paths. */
  def registeredPaths: Set[String] = {
    import scala.jdk.CollectionConverters.*
    queues.keySet().asScala.toSet
  }
}

final case class WebhookTriggerImpl(spec: WebhookTrigger,
                                    id: Id[Step] = Step.id()) extends Trigger derives RW {
  override def name: String = "Webhook"
  override def mode: TriggerMode = TriggerMode.Branch

  override def register(workflow: Workflow): Task[Json] = Task {
    WebhookTrigger.queueFor(spec.path)  // create the queue
    obj("path" -> str(spec.path), "registered" -> str("ok"))
  }

  override def check(workflow: Workflow): Task[Option[Json]] = Task {
    Option(WebhookTrigger.queueFor(spec.path).poll())
  }

  override def unregister(workflow: Workflow): Task[Unit] = Task {
    WebhookTrigger.removeQueue(spec.path)
  }
}
