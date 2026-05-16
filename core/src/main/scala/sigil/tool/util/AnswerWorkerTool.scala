package sigil.tool.util

import fabric.{bool, obj, str}
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.{EventState, WorkerAnswer}
import sigil.tool.model.{AnswerWorkerInput, ResponseContent}
import sigil.tool.{ToolExample, ToolName, TypedTool}

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
case object AnswerWorkerTool
  extends TypedTool[AnswerWorkerInput](
    name = ToolName("answer_worker"),
    description =
      """Respond to a worker's pending question and unblock its run. Pass `taskId` (the worker's
        |run id) + `questionId` (from the worker's ask_parent call) + `answer` (your response).
        |The worker resumes from where it suspended with the answer available in its next
        |iteration's context.""".stripMargin,
    examples = List(
      ToolExample(
        "Answer a worker's clarification request",
        AnswerWorkerInput(
          taskId = "wf-abc-123",
          questionId = "q1",
          answer = "Use 24h tokens, matching our existing flows."
        )
      )
    ),
    keywords = Set("answer", "worker", "respond", "unblock", "clarify")
  ) {
  override def paginate: Boolean = false


  override protected def executeTyped(input: AnswerWorkerInput, ctx: TurnContext): Stream[Event] = Stream.force {
    ctx.sigil.publish(WorkerAnswer(input.taskId, input.questionId, input.answer)).map { _ =>
      val payload = obj(
        "ok"         -> bool(true),
        "taskId"     -> str(input.taskId),
        "questionId" -> str(input.questionId)
      )
      Stream.emit[Event](Message(
        participantId  = ctx.caller,
        conversationId = ctx.conversation.id,
        topicId        = ctx.conversation.currentTopicId,
        content        = Vector(ResponseContent.Text(fabric.io.JsonFormatter.Compact(payload))),
        state          = EventState.Complete,
        role           = MessageRole.Tool
      ))
    }
  }
}
