package sigil.workflow

import sigil.db.SigilDB

/**
 * lightdb collection mix-in adding the workflow stores —
 * `workflowTemplates` (Sigil-side template identity) and `workflows`
 * (Strider's run-state) — to a [[SigilDB]] subclass. Apps that pull
 * in `sigil-workflow` declare their concrete DB as
 * `class MyAppDB(...) extends SigilDB(...) with WorkflowCollections`,
 * then refine `type DB = MyAppDB` on their Sigil instance via
 * [[WorkflowSigil]].
 *
 * Both stores live in the host [[SigilDB]], so they inherit its store
 * manager — RocksDB + Lucene on disk, or Postgres when
 * `sigil.postgres.jdbcUrl` is set. The workflow runtime is durable
 * wherever the rest of the framework's data is durable; Strider only
 * needs a `Collection[Workflow, AbstractWorkflowModel]` to operate.
 */
trait WorkflowCollections { self: SigilDB =>
  val workflowTemplates: S[WorkflowTemplate, WorkflowTemplate.type] = store(WorkflowTemplate)()
  val workflows: S[strider.Workflow, SigilWorkflowModel.type] = store(SigilWorkflowModel)()
}
