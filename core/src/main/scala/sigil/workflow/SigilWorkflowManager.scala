package sigil.workflow

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{Conversation, Topic}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.workflow.event.{TaskExecuted, WorkflowRunCompleted, WorkflowRunFailed, WorkflowRunStarted, WorkflowStepCompleted}
import strider.{AbstractWorkflowManager, Workflow, WorkflowActivity, WorkflowParent}
import strider.step.Step

/**
 * Concrete Strider [[AbstractWorkflowManager]] for Sigil. Bridges
 * the engine's lifecycle hooks into [[Sigil.publish]] so workflow
 * runs are visible in their originating conversation as Sigil
 * Events, and resolves [[WorkflowParent]] sourceIds against the
 * persisted [[WorkflowTemplate]] collection.
 *
 * One manager per Sigil instance; held by [[WorkflowSigil]] as a
 * lazy val and initialized on first access.
 */
final class SigilWorkflowManager(host: Sigil { type DB <: sigil.db.SigilDB & WorkflowCollections },
                                 workflows: lightdb.store.Collection[Workflow, SigilWorkflowModel.type],
                                 maxConcurrent: Int = 1)
  extends AbstractWorkflowManager[WorkflowParent, SigilWorkflowModel.type](
    workflows, maxConcurrent
  ) {

  /** Resolve sourceId to the persisted Sigil-side template. The
    * mapping is direct: the Strider `sourceId` is the
    * `WorkflowTemplate._id` value. */
  override protected def resolveParent(sourceId: Id[WorkflowParent]): Task[Option[WorkflowParent]] =
    host.withDB(_.workflowTemplates.transaction(_.get(Id[WorkflowTemplate](sourceId.value))))
      .map(_.map(SigilWorkflowParent.apply))

  override protected def onWorkflowStarted(workflow: Workflow): Task[Unit] =
    publishLifecycle(workflow) { case (caller, convId, topicId) =>
      WorkflowRunStarted(
        participantId = caller, conversationId = convId, topicId = topicId,
        workflowId = workflow.sourceId.value, workflowName = workflow.name,
        runId = workflow._id.value
      )
    }

  override protected def onWorkflowCompleted(workflow: Workflow): Task[Unit] =
    publishLifecycle(workflow) { case (caller, convId, topicId) =>
      WorkflowRunCompleted(
        participantId = caller, conversationId = convId, topicId = topicId,
        workflowId = workflow.sourceId.value, workflowName = workflow.name,
        runId = workflow._id.value
      )
    }.flatMap(_ => maybePublishTaskExecuted(workflow, exhaustedOnFailure = None))

  override protected def onWorkflowFailed(workflow: Workflow): Task[Unit] = {
    val reason = SigilWorkflowManager.extractFailureReason(workflow)
    publishLifecycle(workflow) { case (caller, convId, topicId) =>
      WorkflowRunFailed(
        participantId = caller, conversationId = convId, topicId = topicId,
        workflowId = workflow.sourceId.value, workflowName = workflow.name,
        runId = workflow._id.value, reason = reason
      )
    }.flatMap(_ => maybePublishTaskExecuted(workflow, exhaustedOnFailure = Some(reason)))
  }

  override protected def onStepCompleted(workflow: Workflow, stepId: Id[Step], success: Boolean): Task[Unit] =
    publishLifecycle(workflow) { case (caller, convId, topicId) =>
      val stepName = workflow.byStepId(stepId).map(_.name).getOrElse(stepId.value)
      WorkflowStepCompleted(
        participantId = caller, conversationId = convId, topicId = topicId,
        workflowId = workflow.sourceId.value, runId = workflow._id.value,
        stepId = stepId.value, stepName = stepName, success = success
      )
    }

  /** Publish a [[TaskExecuted]] echo into the worker's parent
    * conversation when the settled workflow contains an
    * [[SigilAgentDecisionStep]] AND its worker conv has a
    * `parentConversationId`. Carries the worker's summary, role
    * name, iteration count, and `exhausted` flag so the parent
    * agent's next iteration sees a clean task-completion signal.
    *
    * `exhaustedOnFailure = Some(reason)` is the failure path:
    * the worker terminated without a settled step-result payload,
    * so we synthesise `exhausted = true` with the failure reason
    * as the summary. The parent agent reads it as "worker died
    * during init" rather than waiting for a worker that already
    * died. `None` is the happy path — sources summary / iteration
    * from the final step's settled payload.
    *
    * Non-worker runs and orphan workers (no parent conv) emit
    * nothing here; their `WorkflowRunCompleted` / `WorkflowRunFailed`
    * stays the only signal. */
  private def maybePublishTaskExecuted(workflow: Workflow,
                                       exhaustedOnFailure: Option[String]): Task[Unit] = {
    val agentSteps = workflow.steps.collect { case s: SigilAgentDecisionStep => s }
    if (agentSteps.isEmpty) return Task.unit

    val workerConvIdOpt = workflow.conversationId.map(s => Id[Conversation](s))
    workerConvIdOpt match {
      case None => Task.unit
      case Some(workerConvId) =>
        host.withDB(_.conversations.transaction(_.get(workerConvId))).flatMap {
          case None => Task.unit
          case Some(workerConv) => workerConv.parentConversationId match {
            case None => Task.unit
            case Some(parentConvId) =>
              host.withDB(_.conversations.transaction(_.get(parentConvId))).flatMap {
                case None => Task.unit
                case Some(parentConv) =>
                  // Pull the latest AgentDecisionStep's settle payload
                  // — that's where the summary text lives on the
                  // happy path. On failure there's no settled payload,
                  // so the synthesised reason carries the meaning.
                  val finalResult: Option[fabric.Json] = workflow.stepResults.headOption.flatMap(_.output)
                  val payloadSummary    = finalResult.flatMap(_.get("summary").map(_.asString)).getOrElse("")
                  val payloadExhausted  = finalResult.flatMap(_.get("exhausted").map(_.asBoolean)).getOrElse(false)
                  val payloadIterations = finalResult.flatMap(_.get("iteration").map(_.asInt)).getOrElse(0) + 1

                  val (summary, exhausted, iterations) = exhaustedOnFailure match {
                    case Some(reason) => (s"Worker init failed: $reason", true, payloadIterations)
                    case None         => (payloadSummary, payloadExhausted, payloadIterations)
                  }
                  val roleName = agentSteps.head.input.role.name

                  val participantOpt = parentConv.participants.headOption.map(_.id)
                  participantOpt match {
                    case None => Task.unit
                    case Some(pid) =>
                      val ev = TaskExecuted(
                        participantId         = pid,
                        conversationId        = parentConvId,
                        topicId               = parentConv.currentTopicId,
                        taskId                = workflow._id.value,
                        roleName              = roleName,
                        summary               = summary,
                        iterations            = iterations,
                        exhausted             = exhausted,
                        workerConversationId  = Some(workerConvId)
                      )
                      host.publish(ev).handleError(t =>
                        Task(scribe.warn(s"TaskExecuted publish failed for run ${workflow._id.value}: ${t.getMessage}"))
                      )
                  }
              }
          }
        }
    }
  }

  /** Helper — when a workflow run carries a `conversationId`,
    * publish the supplied lifecycle Event into that conversation
    * via the host's `publish` pipeline. Cron-fired runs without a
    * conversation context produce nothing (intentional silent
    * background path).
    *
    * `participantId` resolution, in order of preference:
    *   1. The workflow's `createdBy` matched against the source
    *      conversation's participants list.
    *   2. The source conversation's first participant.
    *   3. For worker conversations spawned by `delegate_task`
    *      (`participants = Nil`, `parentConversationId =
    *      Some(parent)`) — the parent conversation's first
    *      participant. Without this fallback the framework drops
    *      every lifecycle event for worker runs because their
    *      worker conv has no participants by construction.
    *
    * If no participant resolves through any of these, log a
    * warning and skip — the workflow is genuinely unowned. */
  private def publishLifecycle(workflow: Workflow)
                              (build: (ParticipantId, Id[Conversation], Id[Topic]) => Event): Task[Unit] =
    workflow.conversationId match {
      case None => Task.unit
      case Some(convIdStr) =>
        val convId = Id[Conversation](convIdStr)
        host.withDB(_.conversations.transaction(_.get(convId))).flatMap {
          case None => Task.unit
          case Some(conv) =>
            resolveCaller(workflow, conv).flatMap {
              case None =>
                Task(scribe.warn(
                  s"publishLifecycle: no resolvable participant for workflow ${workflow._id.value} on conversation $convId — lifecycle event suppressed."
                ))
              case Some(pid) =>
                val event = build(pid, convId, conv.currentTopicId)
                host.publish(event).handleError(t =>
                  Task(scribe.warn(s"publishLifecycle: publish failed for run ${workflow._id.value}: ${t.getMessage}"))
                )
            }
        }
    }

  /** Locate a participant to attribute a workflow's lifecycle
    * event to. Walks the resolution chain documented on
    * [[publishLifecycle]] — `createdBy` match first, then the
    * source conv's head participant, then the parent conv's
    * head participant (for worker delegations). */
  private def resolveCaller(workflow: Workflow,
                            conv: Conversation): Task[Option[ParticipantId]] = {
    val createdByValue = workflow.createdBy.getOrElse("")
    val matched = conv.participants.find(_.id.value == createdByValue).map(_.id)
    val local = matched.orElse(conv.participants.headOption.map(_.id))
    local match {
      case some @ Some(_) => Task.pure(some)
      case None =>
        conv.parentConversationId match {
          case None => Task.pure(None)
          case Some(parentId) =>
            host.withDB(_.conversations.transaction(_.get(parentId))).map {
              case None => None
              case Some(parentConv) =>
                parentConv.participants.find(_.id.value == createdByValue).map(_.id)
                  .orElse(parentConv.participants.headOption.map(_.id))
            }
        }
    }
  }
}

object SigilWorkflowManager {
  /** Pull the most-recent `StepFailure` error message out of a
    * workflow's history. Falls back to a generic marker when the
    * workflow finished without a step-level failure entry (timed
    * out before reaching the first step, cancelled, etc.). The
    * `StepFailure.errorMessage` carries the exception's `getMessage`
    * — kept short for client UI rendering; full stack trace stays
    * in `log.log`. */
  def extractFailureReason(workflow: Workflow): String =
    workflow.history.iterator.map(_.activity).collectFirst {
      case WorkflowActivity.StepFailure(_, message) => message
      case WorkflowActivity.TimedOut(_)             => "Workflow timed out"
      case WorkflowActivity.Cancelled               => "Workflow cancelled"
    }.getOrElse("unknown")
}

/** Adapter: wraps a Sigil [[WorkflowTemplate]] as Strider's
  * [[WorkflowParent]] so the engine's recycle / parent-resolution
  * paths see the right `workflow` definition. The wrapped workflow
  * is the empty placeholder — recycling rebuilds steps from the
  * template each time. */
final case class SigilWorkflowParent(template: WorkflowTemplate) extends WorkflowParent {
  override def workflow: Workflow = Workflow(
    name = template.name,
    steps = Nil,
    scheduled = System.currentTimeMillis(),
    queue = Nil,
    sourceId = Id(template._id.value)
  )
  override def description: String = template.description.getOrElse("")
  override def enabled: Boolean = template.enabled
}
