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
 *
 * Used in conjunction with `ask_parent`: an `AgentDecisionStep`
 * that emits an `AskParent: <question>` marker translates to
 * `[SendToParentStep, TriggerStep(AnswerTrigger), AgentDecisionStep]`.
 * The TriggerStep blocks until the parent calls `answer_worker`
 * (which publishes the matching `WorkerAnswer` Notice); on resume
 * the answer payload is in workflow.payloads for the next step.
 */
final case class AnswerTrigger(taskId: String,
                               questionId: String) extends WorkflowTrigger derives RW {

  override def kind: String = AnswerTrigger.Kind

  override def compile(host: Sigil): Trigger = AnswerTriggerImpl(this)
}

object AnswerTrigger {
  val Kind: String = "answer"
}

/** Strider-side implementation that bridges the Sigil signal stream
  * into the trigger's `register/check/unregister` lifecycle. Same
  * pattern as [[ConversationMessageTriggerImpl]]: subscribe on
  * register, accumulate into a per-instance queue, drain via check.
  */
final case class AnswerTriggerImpl(spec: AnswerTrigger,
                                   id: Id[Step] = Step.id())
  extends Trigger derives RW {

  override def name: String = "Answer"
  override def mode: TriggerMode = TriggerMode.Continue

  @transient @volatile private var fiber: rapid.Fiber[Unit] = uninitialized
  @transient private val running = new AtomicBoolean(false)
  @transient private val matched = new java.util.concurrent.ConcurrentLinkedQueue[WorkerAnswer]()

  override def register(workflow: Workflow): Task[Json] = Task {
    val sigil = WorkflowHost.get
    running.set(true)
    fiber = sigil.signals
      .takeWhile(_ => running.get())
      .collect { case w: WorkerAnswer => w }
      .filter(w => w.taskId == spec.taskId && w.questionId == spec.questionId)
      .evalMap(w => Task { matched.add(w); () })
      .drain
      .start()
    obj("taskId" -> str(spec.taskId), "questionId" -> str(spec.questionId), "registered" -> str("ok"))
  }

  override def check(workflow: Workflow): Task[Option[Json]] = Task {
    Option(matched.poll()).map { w =>
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
    matched.clear()
  }
}
