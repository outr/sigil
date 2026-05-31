package sigil.workflow

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{Conversation, Topic}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.workflow.event.{WorkflowRunCompleted, WorkflowRunFailed, WorkflowRunStarted, WorkflowStepCompleted}
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
    }

  override protected def onWorkflowFailed(workflow: Workflow): Task[Unit] = {
    val reason = SigilWorkflowManager.extractFailureReason(workflow)
    publishLifecycle(workflow) { case (caller, convId, topicId) =>
      WorkflowRunFailed(
        participantId = caller, conversationId = convId, topicId = topicId,
        workflowId = workflow.sourceId.value, workflowName = workflow.name,
        runId = workflow._id.value, reason = reason
      )
    }
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
    *   3. For a participant-less conversation that descends from a
    *      parent (`parentConversationId = Some(parent)`) — the parent
    *      conversation's first participant, so a run anchored on such a
    *      conversation still has an owner to attribute its lifecycle
    *      events to.
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
    * source conv's head participant, then (for a participant-less
    * conversation) the parent conv's head participant. */
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
