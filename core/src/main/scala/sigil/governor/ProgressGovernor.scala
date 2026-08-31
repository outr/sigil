package sigil.governor

import rapid.Task
import sigil.orchestrator.Directive
import sigil.{ForcedSynthesisReason, Sigil}

/**
 * Progress guard — the checkpoint / planner reflection plus the
 * model-independent hard-stall backstop. Second in the default
 * [[sigil.Sigil.turnGovernors]] order, so its work only runs at
 * boundaries the budget gate did not claim.
 *
 * On a checkpoint iteration it runs the LLM reflection (or the planner
 * consult, when a planner model is configured). Off-interval boundaries
 * skip the reflection but still run the cheap hard-stall check: a model
 * re-emitting the same call past
 * [[sigil.Sigil.hardStallIdenticalCallLimit]] has ignored every
 * cooperative guard, so it surfaces as a terminal intervention instead
 * of grinding to `maxAgentIterations` and throwing.
 *
 * An intervention ALWAYS routes the directive to the AGENT — a
 * Tool-role, Agents-visibility message under a synthetic
 * `_stall_detected` invoke — never a framework-authored user-facing
 * message in the agent's voice. The agent then decides what to do:
 * continue, or ask the user ITSELF via `respond` / `respond_options`.
 * Forced synthesis fires only on a hard stall or inside a directed
 * worker (which cannot ask the human and must report to its
 * supervisor); a cooperative main-agent stall is a non-terminal nudge
 * that continues with the full roster.
 */
final class ProgressGovernor(host: Sigil) extends TurnGovernor {
  override def name: String = "progress"

  override def evaluate(ctx: GovernorContext): Task[GovernorVote] = {
    val agent = ctx.agent
    val conv = ctx.conversation
    val convId = ctx.conversationId
    // The checkpoint runs in every conversation, workers included. It
    // bundles two mechanisms: a mechanical stall detector
    // (repeated-identical-call / no-progress streak) that is universally
    // useful, and an LLM self-assessment that can ask the user. The
    // user-facing escalation misfires in a worker — the supervisor owns
    // asking the human — so an `askingUser` intervention is redirected to
    // a supervisor handoff there rather than suppressing the whole
    // checkpoint and letting a grinding worker flail to the cap.
    val checkpointTask: Task[Option[CheckpointIntervention]] =
      if (ctx.checkpointInterval > 0 && ctx.nextIteration % ctx.checkpointInterval == 0)
        host.runProgressCheckpoint(
          agent,
          convId,
          ctx.claimed,
          ctx.nextIteration,
          ctx.modelProfile,
          ctx.plannerCadence)
      else
        host.evaluateHardStall(convId, agent.id).map(_.map(reason =>
          CheckpointIntervention(
            directive = Directive.ProgressCheckpoint(reason, None),
            askingUser = false,
            terminal = true
          )))
    checkpointTask.map {
      case Some(intervention) =>
        // The ask-the-user escalation misfires in a directed worker —
        // its supervisor owns the human — so it is redirected there.
        // Every other shape publishes the directive the checkpoint
        // built, so the persisted payload and the prose match.
        val directive: Directive =
          if (intervention.askingUser && host.isDirectedWorkerConversation(conv))
            Directive.StallAskSupervisor
          else intervention.directive
        GovernorVote.Intervene(
          Task.defer(host.publishInternalDirective(agent, conv, directive)),
          if (intervention.terminal || host.isDirectedWorkerConversation(conv))
            Some(ForcedSynthesisReason.StallIntervention)
          else None
        )
      case None => GovernorVote.Proceed
    }
  }
}
