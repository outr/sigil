package sigil.tool.util

import sigil.conversation.ActiveSkillSlot

/**
 * The doer framing (#348) activated on a delegated WORKER's own projection
 * in its sub-conversation — symmetric to [[WorkerSupervisorSkill]] on the
 * supervisor. Without it the worker runs the generic top-level agent
 * prompt and, handed a big-sounding brief, applies the same "this is
 * large, delegate it" strategy a top-level agent would — re-delegating a
 * near-identical copy of its own assignment to a clone.
 *
 * Carried as a [[sigil.conversation.SkillSource.Worker]]-keyed slot so it
 * renders in the worker's "Active skills" section only inside its own
 * sub-conversation.
 */
object WorkerSelfSkill {
  val name = "worker_doer"

  def slot(brief: String, role: String): ActiveSkillSlot =
    ActiveSkillSlot(
      name = name,
      content =
        s"""You are the delegated worker for this task — a "$role". You were spawned by a supervisor
           |to carry out the brief below YOURSELF and report the result back via `respond`. The brief is:
           |
           |  "$brief"
           |
           |This brief IS your job. Execute it directly with the tools available to you; do not look for a
           |way to hand it off. In particular:
           |  - Do NOT re-delegate your whole assignment. Calling `delegate_task` with a brief that
           |    restates this one just spawns a clone that faces the same task — it makes no progress and
           |    wastes budget. You are the doer here, not another supervisor.
           |  - You MAY delegate a genuinely SEPARABLE sub-task (a distinct, smaller piece you've already
           |    started decomposing) when that clearly helps — but only after you've begun the work and
           |    identified the sub-task, never as your first move and never for the brief as a whole.
           |  - When you have a question only your supervisor or the user can answer, `respond` with it
           |    and yield; when the work is done, `respond` with the result. Your supervisor reads your
           |    responses and relays what matters upward.
           |
           |Start by doing the work.""".stripMargin
    )
}
