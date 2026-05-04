package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `answer_worker` — the parent agent's response to a
 * previously-asked worker question. Publishes a
 * [[sigil.signal.WorkerAnswer]] Notice that the worker's
 * suspended [[sigil.workflow.trigger.AnswerTrigger]] picks up,
 * resuming the run.
 *
 * `taskId` is the worker's workflow run id (returned from
 * `delegate_task`). `questionId` matches the id the worker emitted
 * with its `ask_parent` call — workers can have multiple questions
 * in flight; the trigger matches on this id to route correctly.
 */
case class AnswerWorkerInput(taskId: String,
                             questionId: String,
                             answer: String) extends ToolInput derives RW
