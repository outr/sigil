package sigil.workflow.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.signal.Notice

/**
 * Notice emitted into the originating conversation when a workflow run
 * pauses on a [[sigil.workflow.SigilApproval]] step. UI clients render
 * the prompt + options so a human can pick one; agents acting as the
 * user's proxy translate the selection into a `resume_workflow` tool
 * call (`runId` + `stepId` + chosen option).
 *
 * Notices aren't persisted, so reconnecting clients that missed the
 * pulse fall back to `list_workflows` (status = WaitingForApproval) to
 * rediscover pending approvals.
 */
case class WorkflowApprovalRequested(conversationId: Id[Conversation],
                                     runId: String,
                                     stepId: String,
                                     stepName: String,
                                     prompt: String,
                                     options: List[String],
                                     timeoutMs: Option[Long]) extends Notice derives RW
