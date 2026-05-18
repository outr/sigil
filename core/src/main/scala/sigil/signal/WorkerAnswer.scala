package sigil.signal

import fabric.rw.*

/**
 * Notice carrying the parent's answer to a worker's `ask_parent`
 * question. Published by `answer_worker(taskId, questionId, answer)`;
 * consumed by [[sigil.workflow.trigger.AnswerTrigger]] which is
 * holding a worker's run suspended in a `TriggerStep` until the
 * matching answer arrives.
 *
 * `taskId` is the worker's workflow run id; `questionId` distinguishes
 * one of potentially several outstanding questions on the same
 * worker. The trigger filters on both — questions stay routable even
 * if a worker asks several in flight.
 */
case class WorkerAnswer(taskId: String,
                        questionId: String,
                        answer: String)
  extends Notice derives RW
