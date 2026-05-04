package sigil.workflow

import lightdb.id.Id
import rapid.Task
import sigil.{Sigil, TurnContext}
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.participant.ParticipantId
import strider.Workflow

/**
 * Build a [[TurnContext]] for in-workflow tool execution. Workflows
 * run outside of an agent's normal turn — there's no live
 * conversation view, no curator output, no real participant chain.
 * Tools that need any of those are at risk of mis-behaving when
 * called from a workflow; the synthetic context provides the best
 * approximation:
 *
 *   - `conversation` is loaded from the workflow's
 *     `conversationId` if set, otherwise a placeholder is built
 *     in-memory (one-off ids, no persistence).
 *   - `chain` resolves to the workflow's `createdBy` (matched
 *     against the conversation's participants list); falls back to
 *     the conversation's first participant; ultimately falls back
 *     to a synthetic anonymous chain when the conversation is
 *     itself synthetic.
 *   - `conversationView` is the persisted view (loaded from
 *     `db.views`) when available; otherwise an empty view.
 *   - `turnInput` is empty — no curator runs from a workflow step.
 *
 * Tools that strictly require a real turn (Stop dispatch,
 * topic-shift detection, etc.) won't behave correctly here. The
 * common case (file system tools, web fetches, save_memory,
 * notifications) all run cleanly because they only consult
 * `chain`, `conversation.id`, and `sigil`.
 */
object SyntheticTurnContext {

  def build(host: Sigil, workflow: Workflow): Task[TurnContext] = {
    workflow.conversationId match {
      case None => Task.pure(emptyContext(host))
      case Some(convIdStr) =>
        val convId = Id[Conversation](convIdStr)
        for {
          maybeConv <- host.withDB(_.conversations.transaction(_.get(convId)))
          maybeView <- host.withDB(_.views.transaction(_.get(ConversationView.idFor(convId))))
        } yield maybeConv match {
          case None       => emptyContext(host)
          case Some(conv) =>
            val view = maybeView.getOrElse(ConversationView(
              conversationId = convId,
              _id = ConversationView.idFor(convId)
            ))
            val createdByValue = workflow.createdBy.getOrElse("")
            val matched = conv.participants.find(_.id.value == createdByValue).map(_.id)
            val chain: List[ParticipantId] =
              matched.orElse(conv.participants.headOption.map(_.id)).toList
            TurnContext(
              sigil = host,
              chain = chain,
              conversation = conv,
              conversationView = view,
              turnInput = TurnInput(view)
            )
        }
    }
  }

  /** Fallback when no conversation context exists — synthesize a
    * placeholder conversation with no participants. Useful for
    * cron-fired workflows whose tools don't need conversational
    * grounding (e.g. the file-system or web-fetch tool families). */
  private def emptyContext(host: Sigil): TurnContext = {
    val convId: Id[Conversation] = Conversation.id("workflow-synthetic-" + rapid.Unique())
    val now = lightdb.time.Timestamp()
    val conv = Conversation(
      topics = Nil,
      participants = Nil,
      currentMode = sigil.provider.ConversationMode,
      space = sigil.GlobalSpace,
      created = now,
      modified = now,
      _id = convId
    )
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    TurnContext(
      sigil = host,
      chain = Nil,
      conversation = conv,
      conversationView = view,
      turnInput = TurnInput(view)
    )
  }
}
