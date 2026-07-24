package sigil.workflow

import fabric.Json
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import strider.{Workflow, WorkflowParent}
import strider.step.Step

/**
 * Bridge between Sigil's typed [[WorkflowTemplate]] and Strider's
 * scheduling API. Compiles a template's [[WorkflowStepInput]] tree
 * to Strider steps, threads Sigil-side metadata (`space`,
 * `createdBy`, `conversationId`) onto the resulting `Workflow`,
 * and inserts the row directly so those fields land alongside
 * the engine's machinery.
 *
 * The reason this isn't a method on [[SigilWorkflowManager]] is
 * separation: the manager only knows how to run workflows; the
 * scheduler builds them from Sigil-side templates and is the
 * surface the management tools call.
 */
object WorkflowScheduler {

  /**
   * Schedule a workflow from a persisted template. Returns the
   * inserted `strider.Workflow` row carrying all Sigil-side
   * metadata.
   */
  def scheduleTemplate(host: Sigil { type DB <: sigil.db.SigilDB & WorkflowCollections } & WorkflowSigil,
                       template: WorkflowTemplate,
                       variables: Map[String, Json] = Map.empty,
                       triggeredBy: Option[ParticipantId] = None): Task[Workflow] = {
    // Force manager init — the engine's monitor loop must be
    // running before our insert lands so the trigger that marks
    // `changed` actually wakes up the executor. Idempotent on
    // subsequent calls (lazy val).
    val _ = host.workflowManager

    given stepRW: RW[Step] = SigilWorkflowModel.stepRW
    val compiled = WorkflowStepInputCompiler.compile(template.steps)
    val source = Id[WorkflowParent](template._id.value)
    val creator: Option[ParticipantId] = triggeredBy.orElse(template.createdBy)
    val effectiveCreatedBy = creator.map(_.value)
    val now = System.currentTimeMillis()

    // Sigil #376 — give the run its OWN openable sub-conversation under the
    // scheduling conversation (mirrors delegate_task's worker conv): the run's
    // lifecycle events land in it, `SyntheticTurnContext` resolves against it
    // (falling through to the parent's participant for the chain), and the UI's
    // worker-pill click-to-open lights up for a workflow row because its run
    // carries a distinct conversationId (≠ the bound conv). Falls back to the
    // bound conversation directly when there's no scheduling conv or no
    // resolvable creator (e.g. a cron-fired run with neither).
    val runConvIdTask: Task[Option[Id[Conversation]]] = (template.conversationId, creator) match {
      case (Some(boundId), Some(c)) =>
        host.newConversation(
          createdBy = c,
          label = template.name,
          summary = template.description.getOrElse(template.name).take(80),
          participants = Nil,
          parentConversationId = Some(boundId)
        ).map(conv => Some(conv._id))
      case _ => Task.pure(template.conversationId)
    }

    runConvIdTask.flatMap { runConvId =>
      val workflow = Workflow(
        name = template.name,
        steps = compiled.steps,
        scheduled = now,
        queue = compiled.queue,
        sourceId = source,
        variableDefs = template.variableDefs,
        variables = variables,
        tags = template.tags,
        space = Some(template.space.value),
        createdBy = effectiveCreatedBy,
        conversationId = runConvId.map(_.value),
        history = List(
          strider.WorkflowHistory(strider.WorkflowActivity.Scheduled(now)),
          strider.WorkflowHistory(strider.WorkflowActivity.Created)
        )
      ).withVariableDefaults
      val missing = workflow.validateVariables
      if (missing.nonEmpty)
        Task.error(new IllegalArgumentException(s"Missing required variables: ${missing.mkString(", ")}"))
      else
        host.withDB(_.workflows.transaction(_.insert(workflow)))
    }
  }
}
