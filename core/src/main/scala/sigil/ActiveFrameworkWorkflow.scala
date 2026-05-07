package sigil

import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Snapshot record of an in-flight framework workflow. Returned by
 * [[Sigil.activeFrameworkWorkflows]] for the activity-list surface
 * and consulted by [[Sigil.cancelFrameworkWorkflow]] for routing
 * cancellation requests. Bug #51.
 */
final case class ActiveFrameworkWorkflow(workflowId: String,
                                         workflowType: String,
                                         label: String,
                                         conversationId: Option[Id[Conversation]],
                                         startedAtMillis: Long,
                                         cancellationToken: CancellationToken)
