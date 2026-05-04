package sigil.workflow

import sigil.db.SigilDB

/**
 * lightdb collection mix-in that adds the `workflowTemplates` store
 * to a [[SigilDB]] subclass. Apps that pull in `sigil-workflow`
 * declare their concrete DB as
 * `class MyAppDB(...) extends SigilDB(...) with WorkflowCollections`,
 * then refine `type DB = MyAppDB` on their Sigil instance via
 * [[WorkflowSigil]].
 *
 * Strider's own collections (`Workflow`, progress, etc.) live in the
 * Strider-managed LightDB — see [[WorkflowModel]]. We keep templates
 * separate because they're Sigil-side identity (space, createdBy,
 * conversationId) that Strider's engine doesn't model.
 */
trait WorkflowCollections { self: SigilDB =>
  val workflowTemplates: S[WorkflowTemplate, WorkflowTemplate.type] = store(WorkflowTemplate)()
}
