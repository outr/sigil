package sigil.workflow

import lightdb.id.Id
import rapid.Task
import sigil.{Sigil, SpaceId}
import sigil.conversation.{Conversation, Topic}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.workflow.event.{WorkflowRunCompleted, WorkflowRunFailed, WorkflowRunStarted, WorkflowStepCompleted}
import strider.{AbstractWorkflowManager, Workflow, WorkflowParent, WorkflowStatus}
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
                                 workflowDb: SigilWorkflowDB,
                                 maxConcurrent: Int = 1)
  extends AbstractWorkflowManager[WorkflowParent, SigilWorkflowModel.type](
    workflowDb.workflows, maxConcurrent
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

  override protected def onWorkflowFailed(workflow: Workflow): Task[Unit] =
    publishLifecycle(workflow) { case (caller, convId, topicId) =>
      val reason = workflow.history
        .collectFirst { case h if h.activity.toString.contains("Failure") => h.activity.toString }
        .getOrElse("unknown")
      WorkflowRunFailed(
        participantId = caller, conversationId = convId, topicId = topicId,
        workflowId = workflow.sourceId.value, workflowName = workflow.name,
        runId = workflow._id.value, reason = reason
      )
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
    * `participantId` resolution: prefer the workflow's `createdBy`
    * (matched against the conversation's participants list);
    * fall back to the first participant if the createdBy isn't a
    * member; emit nothing if the conversation has no participants
    * (effectively unowned — a corner case that shouldn't happen). */
  private def publishLifecycle(workflow: Workflow)
                              (build: (ParticipantId, Id[Conversation], Id[Topic]) => Event): Task[Unit] =
    workflow.conversationId match {
      case None =>
        scribe.warn(s"publishLifecycle: workflow ${workflow._id.value} has no conversationId — no Event published")
        Task.unit
      case Some(convIdStr) =>
        val convId = Id[Conversation](convIdStr)
        host.withDB(_.conversations.transaction(_.get(convId))).flatMap {
          case None =>
            scribe.warn(s"publishLifecycle: conversation $convIdStr not found — no Event published")
            Task.unit
          case Some(conv) =>
            val createdByValue = workflow.createdBy.getOrElse("")
            val matched = conv.participants.find(_.id.value == createdByValue).map(_.id)
            val caller = matched.orElse(conv.participants.headOption.map(_.id))
            caller match {
              case None      =>
                scribe.warn(s"publishLifecycle: conversation $convIdStr has no participants — no Event published")
                Task.unit
              case Some(pid) =>
                val event = build(pid, convId, conv.currentTopicId)
                scribe.info(s"publishLifecycle: emitting ${event.getClass.getSimpleName} for run ${workflow._id.value}")
                host.publish(event).handleError(t =>
                  Task(scribe.warn(s"publishLifecycle: publish failed: ${t.getMessage}"))
                )
            }
        }
    }
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
  override def description: String = template.description
  override def enabled: Boolean = template.enabled
}
