package sigil.workflow.trigger

import fabric.{Json, Null, obj, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.{FiberOps, Task}
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.Message
import sigil.tool.model.ResponseContent
import sigil.workflow.{WorkflowHost, WorkflowTrigger}
import strider.Workflow
import strider.step.{Step, Trigger, TriggerMode}

import java.util.concurrent.atomic.AtomicBoolean
import scala.compiletime.uninitialized

/**
 * Workflow trigger that fires when a new [[Message]] lands in a
 * specific conversation matching an optional content filter.
 *
 * `conversationId` is required — workflows scoped to "any
 * message" should specify a conversation explicitly. (Cross-cutting
 * "any conversation" triggers are app-policy and live downstream.)
 *
 * `participantId` (optional) restricts the trigger to messages from
 * a specific participant. `containsText` (optional) filters by
 * substring match. Empty filter means "every message in this
 * conversation."
 *
 * Compiled to a Strider [[Trigger]] that subscribes to the host
 * Sigil's signal stream during `register` and accumulates matching
 * Messages into a buffer; `check` drains the buffer and returns
 * the next match (or `None` if still waiting).
 */
final case class ConversationMessageTrigger(conversationId: String,
                                            participantId: Option[String] = None,
                                            containsText: Option[String] = None)
  extends WorkflowTrigger derives RW {

  override def kind: String = ConversationMessageTrigger.Kind

  override def compile(host: Sigil): Trigger = ConversationMessageTriggerImpl(this)
}

object ConversationMessageTrigger {
  val Kind: String = "conversation_message"
}

/** Strider-side trigger implementation that bridges the Sigil
  * signal stream into the workflow's `register/check/unregister`
  * lifecycle. Per-trigger-instance state lives in a small atomic
  * buffer so Strider's polling `check` can drain matches without
  * blocking on the signal subscription.
  */
final case class ConversationMessageTriggerImpl(spec: ConversationMessageTrigger,
                                                id: Id[Step] = Step.id())
  extends Trigger derives RW {

  override def name: String = "ConversationMessage"
  override def mode: TriggerMode = TriggerMode.Continue

  // Per-instance subscription handle and matched-message queue.
  // Stored at the lifecycle of the running workflow — re-registered
  // if the workflow is resumed after a crash. `running` flips false
  // on `unregister` so the upstream `takeWhile` terminates the
  // stream and the SignalHub subscription is released — independent
  // of `rapid.Fiber.cancel` (which is a stub today).
  @transient @volatile private var fiber: rapid.Fiber[Unit] = uninitialized
  @transient private val running = new AtomicBoolean(false)
  @transient private val matched = new java.util.concurrent.ConcurrentLinkedQueue[Message]()

  private def messageText(m: Message): String =
    m.content.collect { case ResponseContent.Text(t) => t }.mkString

  override def register(workflow: Workflow): Task[Json] = Task {
    val sigil = WorkflowHost.get
    val convId = Id[Conversation](spec.conversationId)
    running.set(true)
    fiber = sigil.signals
      .takeWhile(_ => running.get())
      .collect { case m: Message if m.conversationId == convId => m }
      .filter(m => spec.participantId.forall(p => m.participantId.value == p))
      .filter(m => spec.containsText.forall(needle => messageText(m).contains(needle)))
      .evalMap(m => Task { matched.add(m); () })
      .drain
      .start()
    obj("conversationId" -> str(spec.conversationId), "registered" -> str("ok"))
  }

  override def check(workflow: Workflow): Task[Option[Json]] = Task {
    Option(matched.poll()).map { m =>
      obj(
        "messageId" -> str(m._id.value),
        "participantId" -> str(m.participantId.value),
        "text" -> str(messageText(m))
      ): Json
    }
  }

  override def unregister(workflow: Workflow): Task[Unit] = Task {
    // Flip the gate so the upstream `takeWhile` terminates on the
    // next signal — that releases the SignalHub subscription
    // through the stream's own teardown, no Fiber.cancel needed.
    // We also drop the fiber ref and drain residual matches so a
    // re-register starts cleanly.
    running.set(false)
    fiber = null
    matched.clear()
  }
}
