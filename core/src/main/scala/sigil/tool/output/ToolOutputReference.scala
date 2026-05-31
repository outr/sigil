package sigil.tool.output

import lightdb.filter.FilterExtras
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation

/**
 * Resolves a tool-output *reference* — the `callId` a paginated tool
 * echoes on its first-page result, or any node id from that result — back
 * to the full set of [[ToolOutputNode]] rows it produced. This is the
 * read side of tool composition: tool B takes the event id of tool A's
 * output and scopes its own work to what A surfaced (e.g. grep within the
 * files a prior glob/grep matched).
 *
 * Cross-conversation reads go through [[Sigil.canReadConversation]], so a
 * reference is reachable only from the conversation that produced it, its
 * parent, or one of its workers — the same scope rule [[NextPageTool]] /
 * [[QueryToolOutputTool]] enforce. Failures return a `Left` carrying a
 * message the consuming tool surfaces to the agent (reclaimed reference,
 * cross-conversation denial) rather than throwing.
 */
object ToolOutputReference {

  def resolve(host: Sigil,
              currentConversationId: Id[Conversation],
              reference: String,
              conversationId: Option[Id[Conversation]] = None): Task[Either[String, ResolvedReference]] = {
    val target = conversationId.getOrElse(currentConversationId)
    host.canReadConversation(currentConversationId, target).flatMap {
      case Left(reason) => Task.pure(Left(reason))
      case Right(_) =>
        host.withDB(_.toolOutputs.transaction(_.query.filter(n => n.conversationKey === target.value).toList)).map { all =>
          // A reference may be the originating call id, the synthetic
          // top-level referenceId (== callId.value), or any drained
          // node's id. Any of them anchors us to the owning callId,
          // and we return that call's whole container.
          all.find(n => n._id.value == reference || n.referenceId == reference || n.callId.value == reference) match {
            case None =>
              Left(
                s"reference `$reference` not found in conversation `${target.value}` — it may have been " +
                  "reclaimed; re-run the source tool to produce a fresh reference"
              )
            case Some(anchor) =>
              val rows = all.filter(_.callId == anchor.callId).sortBy(n => (n.level, n.ordinal))
              Right(ResolvedReference(target, anchor.callId, rows))
          }
        }
    }
  }
}
