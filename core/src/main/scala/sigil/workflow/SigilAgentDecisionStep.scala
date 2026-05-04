package sigil.workflow

import fabric.{Json, bool, num, obj, str}
import fabric.rw.*
import lightdb.id.Id
import lightdb.progress.ProgressManager
import rapid.Task
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole, MessageVisibility}
import sigil.provider.{GenerationSettings, OneShotRequest, ProviderEvent}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.workflow.trigger.AnswerTrigger
import strider.Workflow
import strider.step.{Job, Step}

/**
 * Strider executor for [[AgentDecisionStepInput]]. Runs one LLM
 * round-trip via [[sigil.Sigil.providerFor]] using the resolved
 * `modelId`, with `role.description` (plus a worker-loop coda)
 * as system prompt and the brief + accumulated reasoning as the
 * user prompt.
 *
 * **Loop shape (v3 — multi-iteration ReAct + ask_parent).** The LLM
 * response is scanned for marker lines:
 *
 *   - `Complete: <summary>` — terminate, capture summary
 *   - `AskParent: <question>` — suspend the run on an
 *     [[AnswerTrigger]] and post the question into the worker
 *     conversation's parent (the user-facing conversation) as a
 *     hidden-from-user `Message`. The parent agent fires on that
 *     Message, decides whether to answer from context or escalate
 *     to the user, and eventually calls `answer_worker` which
 *     publishes a [[sigil.signal.WorkerAnswer]] Notice that fires
 *     the trigger — the worker resumes
 *   - no marker — append the next [[AgentDecisionStepInput]] with
 *     this iteration's reasoning folded into the chain (regular
 *     ReAct continuation)
 *
 * `iteration >= maxIterations` is a hard runaway cap; the worker
 * settles with `exhausted = true` on the boundary regardless of
 * marker.
 */
final case class SigilAgentDecisionStep(input: AgentDecisionStepInput,
                                        id: Id[Step] = Step.id()) extends Job[Json] derives RW {
  override def name: String = input.name.getOrElse(input.id)

  override def execute(workflow: Workflow, pm: ProgressManager): Task[Json] = {
    val host = WorkflowHost.get
    val modelId = Id[Model](input.modelId)

    host.providerFor(modelId, Nil).flatMap { provider =>
      val systemPrompt = SigilAgentDecisionStep.buildSystemPrompt(input)
      val userPrompt   = SigilAgentDecisionStep.buildUserPrompt(input)
      val request = OneShotRequest(
        modelId            = modelId,
        systemPrompt       = systemPrompt,
        userPrompt         = userPrompt,
        generationSettings = GenerationSettings()
      )
      val acc = new java.lang.StringBuilder
      provider(request).evalMap {
        case ProviderEvent.TextDelta(t)            => Task { acc.append(t); () }
        case ProviderEvent.ContentBlockDelta(_, t) => Task { acc.append(t); () }
        case _                                     => Task.unit
      }.drain.flatMap { _ =>
        val response = acc.toString
        decideNext(host, workflow, response)
      }
    }
  }

  private def decideNext(host: sigil.Sigil, workflow: Workflow, response: String): Task[Json] =
    SigilAgentDecisionStep.parseMarker(response) match {
      case SigilAgentDecisionStep.MarkerCompletion(summary) =>
        Task.pure(obj(
          "complete"  -> bool(true),
          "summary"   -> str(summary),
          "iteration" -> num(input.iteration),
          "exhausted" -> bool(false)
        ): Json)

      case _ if input.iteration + 1 >= input.maxIterations =>
        // Cap reached — settle whatever response we got as the summary.
        Task.pure(obj(
          "complete"  -> bool(true),
          "summary"   -> str(response),
          "iteration" -> num(input.iteration),
          "exhausted" -> bool(true)
        ): Json)

      case SigilAgentDecisionStep.MarkerAskParent(question) =>
        suspendForAnswer(host, workflow, response, question).map { questionId =>
          obj(
            "complete"   -> bool(false),
            "asked"      -> bool(true),
            "questionId" -> str(questionId),
            "question"   -> str(question),
            "iteration"  -> num(input.iteration)
          ): Json
        }

      case SigilAgentDecisionStep.MarkerNone =>
        appendNextIteration(host, workflow, response).map { _ =>
          obj(
            "complete"  -> bool(false),
            "partial"   -> str(response),
            "iteration" -> num(input.iteration)
          ): Json
        }
    }

  /** Suspend the run: post the question into the parent conversation
    * as a hidden-from-user Message, then append a TriggerStep on an
    * AnswerTrigger plus a follow-up AgentDecisionStep that will see
    * the answer in priorReasoning when the trigger fires. */
  private def suspendForAnswer(host: sigil.Sigil, workflow: Workflow, response: String, question: String): Task[String] = host match {
    case ws: WorkflowSigil =>
      val workerConvIdOpt = workflow.conversationId.map(s => Id[Conversation](s))
      val questionId = s"q${input.iteration + 1}-${rapid.Unique()}"
      for {
        _ <- workerConvIdOpt match {
          case Some(workerConvId) => publishToParent(host, workerConvId, questionId, question)
          case None               => Task.unit  // worker has no conv — orphan ask, log only
        }
        triggerStep = TriggerStepInput(
          id      = s"answer-trigger-${input.iteration + 1}",
          name    = Some(s"Awaiting parent answer (q${input.iteration + 1})"),
          trigger = AnswerTrigger(taskId = workflow._id.value, questionId = questionId)
        )
        nextStep = input.copy(
          id              = s"decision-${input.iteration + 1}",
          iteration       = input.iteration + 1,
          priorReasoning  = input.priorReasoning :+ s"$response\n\n[Asked parent: $question — awaiting answer]"
        )
        _ <- {
          import sigil.workflow.SigilWorkflowModel.stepRW
          val compiled = WorkflowStepInputCompiler.compile(List(triggerStep, nextStep))
          ws.workflowManager.updateSteps(workflow._id, workflow.steps ++ compiled.steps).unit
        }
      } yield questionId
    case _ =>
      Task.error(new IllegalStateException(
        "AgentDecisionStep requires the host Sigil to mix in WorkflowSigil. " +
        "ask_parent suspend/resume cannot run without the workflow runtime active."
      ))
  }

  /** Look up the worker conversation, find its parent (the user-
    * facing conversation that delegated the work), and publish a
    * Message there carrying the question with `MessageVisibility.Agents`
    * so the user doesn't see it but the parent agent's TriggerFilter
    * does. Worker convs without a parentConversationId, or parent
    * convs without participants, log a warning and skip — the
    * question is dropped but the worker still suspends on its
    * trigger; an external `answer_worker` call resumes it. */
  private def publishToParent(host: sigil.Sigil, workerConvId: Id[Conversation], questionId: String, question: String): Task[Unit] =
    host.withDB(_.conversations.transaction(_.get(workerConvId))).flatMap {
      case Some(workerConv) => workerConv.parentConversationId match {
        case Some(parentConvId) =>
          host.withDB(_.conversations.transaction(_.get(parentConvId))).flatMap {
            case Some(parentConv) =>
              parentConv.participants.headOption match {
                case Some(senderParticipant) =>
                  val msg = Message(
                    participantId  = senderParticipant.id,
                    conversationId = parentConvId,
                    topicId        = parentConv.currentTopicId,
                    content        = Vector(ResponseContent.Text(s"[Worker question $questionId]: $question")),
                    state          = EventState.Complete,
                    role           = MessageRole.Standard,
                    visibility     = MessageVisibility.Agents
                  )
                  host.publish(msg).unit
                case None =>
                  Task { scribe.warn(s"AgentDecisionStep ask_parent: parent conv $parentConvId has no participants; question dropped"); () }
              }
            case None =>
              Task { scribe.warn(s"AgentDecisionStep ask_parent: parent conv $parentConvId not found for worker $workerConvId"); () }
          }
        case None =>
          Task { scribe.warn(s"AgentDecisionStep ask_parent: worker conv $workerConvId has no parentConversationId; question dropped"); () }
      }
      case None =>
        Task { scribe.warn(s"AgentDecisionStep ask_parent: worker conv $workerConvId not found"); () }
    }

  private def appendNextIteration(host: sigil.Sigil, workflow: Workflow, lastResponse: String): Task[Unit] = host match {
    case ws: WorkflowSigil =>
      val priorReasoning = input.priorReasoning :+ lastResponse
      val nextStep = input.copy(
        id              = s"decision-${input.iteration + 1}",
        iteration       = input.iteration + 1,
        priorReasoning  = priorReasoning
      )
      import sigil.workflow.SigilWorkflowModel.stepRW
      val compiled = WorkflowStepInputCompiler.compile(List(nextStep))
      ws.workflowManager.updateSteps(workflow._id, workflow.steps ++ compiled.steps).unit
    case _ =>
      Task.error(new IllegalStateException(
        "AgentDecisionStep requires the host Sigil to mix in WorkflowSigil. " +
        "Multi-iteration workers can't run without the workflow runtime active."
      ))
  }
}

object SigilAgentDecisionStep {

  /** Parsed marker — drives the next-step decision in `decideNext`. */
  sealed trait Marker
  case class MarkerCompletion(summary: String) extends Marker
  case class MarkerAskParent(question: String) extends Marker
  case object MarkerNone extends Marker

  /** System prompt: role description plus the worker-loop coda
    * (how to terminate, how to ask the parent for clarification).
    * Kept terse — the role's own description does most of the
    * identity work. */
  def buildSystemPrompt(input: AgentDecisionStepInput): String = {
    s"""${input.role.description}
       |
       |You are running as a worker — this is iteration ${input.iteration + 1} of up to ${input.maxIterations}.
       |
       |When you have completed the work the user briefed you on, respond with a single line:
       |  Complete: <one-paragraph summary of what you did and what the result is>
       |
       |If you need clarification or a decision from the parent agent before you can proceed,
       |emit a single line:
       |  AskParent: <your question>
       |The framework suspends your run, routes the question to the parent agent (which may
       |answer from context or escalate to the user), and resumes you with the answer in your
       |context on the next iteration.
       |
       |If you need to keep working (research more, plan further, etc.), just write your reasoning
       |and the framework will give you another turn with your reasoning carried forward. Don't
       |emit `Complete:` or `AskParent:` until you genuinely need them.""".stripMargin
  }

  /** User prompt: the brief plus any accumulated reasoning from
    * prior iterations, so each turn sees the full chain. */
  def buildUserPrompt(input: AgentDecisionStepInput): String = {
    if (input.priorReasoning.isEmpty) input.brief
    else {
      val priors = input.priorReasoning.zipWithIndex.map { case (text, i) =>
        s"--- Iteration ${i + 1} ---\n$text"
      }.mkString("\n\n")
      s"""${input.brief}
         |
         |$priors
         |
         |--- Continue ---""".stripMargin
    }
  }

  /** Scan the response for the first matching marker. `Complete:`
    * wins over `AskParent:` if the LLM emits both — terminating is
    * stronger than waiting. Both are anchored at line start so
    * mid-paragraph mentions don't false-positive. */
  def parseMarker(response: String): Marker = {
    parseCompletion(response) match {
      case Some(summary) => MarkerCompletion(summary)
      case None =>
        parseAskParent(response) match {
          case Some(question) => MarkerAskParent(question)
          case None           => MarkerNone
        }
    }
  }

  /** Pull the post-`Complete:` summary out of the response. */
  def parseCompletion(response: String): Option[String] = {
    val pattern = "(?im)^\\s*Complete:\\s*(.+)$".r
    pattern.findFirstMatchIn(response).map { m =>
      val afterMarker = response.substring(m.start)
      "(?im)^\\s*Complete:\\s*".r.replaceFirstIn(afterMarker, "").trim
    }
  }

  /** Pull the post-`AskParent:` question out of the response. Same
    * anchored / case-insensitive shape as parseCompletion. */
  def parseAskParent(response: String): Option[String] = {
    val pattern = "(?im)^\\s*AskParent:\\s*(.+)$".r
    pattern.findFirstMatchIn(response).map { m =>
      val afterMarker = response.substring(m.start)
      "(?im)^\\s*AskParent:\\s*".r.replaceFirstIn(afterMarker, "").trim
    }
  }
}
