package sigil.skill

import sigil.conversation.ActiveSkillSlot

/**
 * Optional coding-discipline skill: ship the smallest change that
 * actually works, without cutting the safety floor. Nothing attaches
 * it by default — apps opt in wherever the skill machinery accepts a
 * slot:
 *
 *   - on a coding [[sigil.provider.Mode]] (`skill = Some(MinimalImplementationSkill.slot)`)
 *     — the highest-leverage wiring, because `delegate_task` workers
 *     inherit the spawning conversation's mode, so every coding
 *     worker picks the discipline up automatically;
 *   - on a [[sigil.role.Role]] for a specific implementer agent;
 *   - per-projection via `Sigil.activateSkill`.
 *
 * The discipline targets the two failure shapes that dominate
 * agent-written diffs: over-building (a dependency and a wrapper
 * where a native feature was one line) and unrequested prose. The
 * safety floor is explicit — minimalism must never shed validation,
 * error handling, security, or accessibility.
 */
object MinimalImplementationSkill {
  val name: String = "minimal-implementation"

  val text: String =
    """Ship the smallest change that actually works. Before writing code, stop at the FIRST rung that holds:
      |
      |1. Does this need to exist at all? Speculative need = skip it and say so in one line.
      |2. Does this codebase already have it? Reuse the existing helper, type, or pattern — look before writing; re-implementing what lives a few files over is the most common waste.
      |3. Standard library covers it? Use it.
      |4. Native platform feature covers it? Use it — a built-in control over a widget library, CSS over JS, a DB constraint over app code.
      |5. An already-installed dependency covers it? Use it. Never add a NEW dependency for what a few lines can do.
      |6. Can it be one line? Write one line.
      |7. Only then: the minimum code that works.
      |
      |Rules:
      |- Understand before you shrink. Read every file the change touches and trace the real flow end to end FIRST — the ladder shortens the solution, never the reading. A small diff in the wrong place is a second bug, not efficiency.
      |- Fix root causes. A bug report names a symptom; one guard where every caller routes through is a smaller diff than a patch in each caller — and the only fix that doesn't leave siblings broken.
      |- No unrequested abstractions: no interface with one implementation, no factory for one product, no configuration for a value that never changes, no scaffolding "for later".
      |- Prefer deletion over addition, boring over clever.
      |- NEVER simplify away: input validation at trust boundaries, error handling that prevents data loss, security measures, accessibility basics, or anything explicitly requested. If the user insists on the full version, build it without re-arguing.
      |- Mark a deliberate shortcut with a comment naming its ceiling and the upgrade path (e.g. `// shortcut: global lock — move to per-account locks if throughput matters`).
      |- Non-trivial logic leaves ONE runnable check behind — the smallest thing that fails if the logic breaks. Trivial one-liners need none.
      |- Report code first, then at most three short lines: what was skipped and when to add it. Explanation the user explicitly asked for is given in full; unrequested essays are complexity smuggled back in as prose.""".stripMargin

  /**
   * The slot apps attach to a Mode, Role, or projection.
   */
  val slot: ActiveSkillSlot = ActiveSkillSlot(name = name, content = text)
}
