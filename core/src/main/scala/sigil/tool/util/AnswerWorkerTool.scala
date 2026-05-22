package sigil.tool.util

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.signal.WorkerAnswer
import sigil.tool.model.AnswerWorkerInput
import sigil.tool.{Tool, ToolExample, ToolName, ToolOutput, ToolResult}

/** Typed result of [[AnswerWorkerTool]] — confirms the worker's
  * pending question was answered and its run unblocked. */
case class AnswerWorkerOutput(ok: Boolean,
                              taskId: String,
                              questionId: String) extends ToolOutput derives RW

/**
 * `answer_worker` — the parent agent uses this to resolve a worker's
 * outstanding question. Publishes a [[sigil.signal.WorkerAnswer]]
 * Notice that the worker's suspended `TriggerStep(AnswerTrigger)`
 * is listening for; on receipt the trigger fires and the worker's
 * run resumes with the answer in scope.
 *
 * Match keys: `taskId` + `questionId`. Multiple questions in flight
 * route to the right trigger by id.
 */
case object AnswerWorkerTool extends Tool {
  type Input  = AnswerWorkerInput
  type Output = AnswerWorkerOutput
  val inputRW  = summon[RW[AnswerWorkerInput]]
  val outputRW = summon[RW[AnswerWorkerOutput]]
  val name = ToolName("answer_worker")
  val description =
    """Respond to a worker's pending question and unblock its run. Pass `taskId` (the worker's
      |run id) + `questionId` (from the worker's ask_parent call) + `answer` (your response).
      |The worker resumes from where it suspended with the answer available in its next
      |iteration's context.""".stripMargin
  override val examples = List(
    ToolExample(
      "Answer a worker's clarification request",
      AnswerWorkerInput(
        taskId = "wf-abc-123",
        questionId = "q1",
        answer = "Use 24h tokens, matching our existing flows."
      )
    )
  )
  override val keywords = Set("answer", "worker", "respond", "unblock", "clarify")

  override def executeResult(input: AnswerWorkerInput,
                             ctx: TurnContext): Task[ToolResult[AnswerWorkerOutput]] =
    ctx.sigil.publish(WorkerAnswer(input.taskId, input.questionId, input.answer)).map { _ =>
      ToolResult.Success(AnswerWorkerOutput(ok = true, input.taskId, input.questionId))
    }
}
