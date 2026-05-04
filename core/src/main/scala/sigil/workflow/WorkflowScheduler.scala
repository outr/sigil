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

  /** Schedule a workflow from a persisted template. Returns the
    * inserted `strider.Workflow` row carrying all Sigil-side
    * metadata. */
  def scheduleTemplate(host: Sigil { type DB <: sigil.db.SigilDB & WorkflowCollections } & WorkflowSigil,
                       workflowDb: SigilWorkflowDB,
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
    val effectiveCreatedBy = triggeredBy.map(_.value).orElse(template.createdBy.map(_.value))
    val now = System.currentTimeMillis()
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
      conversationId = template.conversationId.map(_.value),
      history = List(
        strider.WorkflowHistory(strider.WorkflowActivity.Scheduled(now)),
        strider.WorkflowHistory(strider.WorkflowActivity.Created)
      )
    ).withVariableDefaults
    val missing = workflow.validateVariables
    if (missing.nonEmpty)
      Task.error(new IllegalArgumentException(s"Missing required variables: ${missing.mkString(", ")}"))
    else
      workflowDb.workflows.transaction(_.insert(workflow))
  }
}
