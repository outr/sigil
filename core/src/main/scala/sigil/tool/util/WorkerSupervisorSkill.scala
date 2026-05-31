package sigil.tool.util

import lightdb.id.Id
import sigil.conversation.{ActiveSkillSlot, Conversation}

/**
 * The bridge guidance (#327) activated on a delegating agent's projection
 * in a worker sub-conversation. Tells the agent how to act as the
 * worker's supervisor: judge whether the worker satisfied the brief, post
 * a follow-up when it hasn't, and relay up to the parent conversation
 * when the worker needs the user or the work is done.
 *
 * Carried as a [[sigil.conversation.SkillSource.Supervisor]]-keyed skill
 * slot so it renders in the agent's "Active skills" section only while it
 * is acting in the worker conversation (the slot lives on the agent's
 * per-conversation projection there).
 */
object WorkerSupervisorSkill {
  val name = "worker_supervisor"

  def slot(brief: String, parentConversationId: Id[Conversation], workerName: String): ActiveSkillSlot =
    ActiveSkillSlot(
      name = name,
      content =
        s"""You delegated a task to a worker agent ("$workerName") in THIS sub-conversation and are
           |supervising it — you are effectively the worker's user. The brief you gave it was:
           |
           |  "$brief"
           |
           |The worker does its work here and responds to you when it has a question or a result. Each
           |time it responds (its turn ends), decide:
           |  - If its response satisfies the brief: use `relay_message` to surface the relevant outcome
           |    into the parent conversation (id `${parentConversationId.value}`) for the user — relay
           |    only what matters, not the worker's raw chatter — and then STOP. Do not reply again in
           |    this worker conversation: leaving the worker un-addressed is what ends the task (it goes
           |    idle and the delegation completes). Replying here instead would needlessly re-engage it.
           |  - If the worker asks something you can answer from your own knowledge or the parent
           |    conversation's context: reply here with the answer (your reply re-engages the worker).
           |  - If the worker needs information only the user has: use `relay_message` to ask in the
           |    parent conversation; relay the user's answer back here when it arrives.
           |  - If the brief is NOT yet satisfied and the worker stalled or went off-track: post a
           |    follow-up message here directing or correcting it.
           |
           |Reply here ONLY when you genuinely need the worker to continue. When the work is done the
           |right move is always: relay the result up to the parent and stop. Keep the parent
           |conversation clean — the worker's detailed work stays here; you bridge only the questions
           |that need the user and the results worth surfacing.""".stripMargin
    )
}
