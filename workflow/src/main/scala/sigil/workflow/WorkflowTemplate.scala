package sigil.workflow

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.{GlobalSpace, SpaceId}
import sigil.SpaceId.given
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.participant.ParticipantId.given

/**
 * A persisted workflow template — the agent-authored shape of a
 * workflow that runs as a [[strider.Workflow]] when scheduled.
 *
 * Sigil-side identity. Each template owns a list of typed
 * [[WorkflowStepInput]] step shapes; the framework compiles those
 * to `strider.step.Step` instances at run time via
 * [[WorkflowStepInputCompiler]]. The reason templates persist as
 * the typed inputs (not the engine-side primitives) is that fabric
 * RW's polymorphic round-trip works for `WorkflowStepInput` —
 * Strider's `Step` is a runtime trait without an RW.
 *
 * `space` scopes the template under Sigil's existing `accessibleSpaces`
 * authz model — same shape as memories, tools, etc. Default
 * `GlobalSpace`.
 *
 * `createdBy` records who authored the template — typically the
 * agent that called `create_workflow`. `conversationId` (optional)
 * threads the template back to the conversation it was authored in,
 * so triggered runs can surface lifecycle Events there.
 *
 * `triggers` is the list of registered [[WorkflowTrigger]]s that
 * fire this template. Empty means "manual-only" — only callable
 * via `run_workflow`.
 *
 * `variableDefs` mirror Strider's variable contract — declared
 * inputs the workflow expects.
 */
case class WorkflowTemplate(name: String,
                            description: Option[String] = None,
                            steps: List[WorkflowStepInput] = Nil,
                            triggers: List[WorkflowTrigger] = Nil,
                            variableDefs: List[strider.WorkflowVariable] = Nil,
                            space: SpaceId = GlobalSpace,
                            createdBy: Option[ParticipantId] = None,
                            conversationId: Option[Id[Conversation]] = None,
                            tags: Set[String] = Set.empty,
                            enabled: Boolean = true,
                            created: Timestamp = Timestamp(),
                            modified: Timestamp = Timestamp(),
                            _id: Id[WorkflowTemplate] = WorkflowTemplate.id())
  extends RecordDocument[WorkflowTemplate]

object WorkflowTemplate extends RecordDocumentModel[WorkflowTemplate] with JsonConversion[WorkflowTemplate] {
  implicit override def rw: RW[WorkflowTemplate] = RW.gen

  // String-projected indexes — Lucene's filter generator can't query
  // polymorphic / case-class fields directly.
  val nameIndex: I[String]            = field.index(_.name)
  val spaceIdValue: I[String]         = field.index(_.space.value)
  val createdByValue: I[Option[String]] = field.index(_.createdBy.map(_.value))
  val conversationIdValue: I[Option[String]] = field.index(_.conversationId.map(_.value))
  val enabledIndex: I[Boolean]        = field.index(_.enabled)
  val tagsIndex: I[Set[String]]       = field.index(_.tags)

  override def id(value: String = rapid.Unique()): Id[WorkflowTemplate] = Id(value)
}
