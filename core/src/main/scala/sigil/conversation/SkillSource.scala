package sigil.conversation

import fabric.rw.*

/**
 * Classifies the provenance of an active skill slot on
 * [[ParticipantProjection.activeSkills]]. Multiple sources can contribute
 * simultaneously — a participant can have a `Mode`-injected skill AND a
 * `Discovery`-promoted skill active at the same time — without
 * overwriting each other, because the map is keyed by `SkillSource`.
 *
 *   - `Mode`           — injected by the current operating mode
 *                        (e.g., switching to `Coding` pulls in
 *                        coding-specific context)
 *   - `Discovery`      — promoted via `find_capability` / capability
 *                        search
 *   - `User`           — explicit user directive ("use this skill")
 *   - `Role(name)`     — contributed by a [[sigil.role.Role]] on the
 *                        participant's `roles` list. Parameterized
 *                        by role name so multiple roles don't clobber
 *                        each other in the map.
 *   - `Supervisor`     — injected on the delegating agent's projection
 *                        in a worker sub-conversation (#327): the bridge
 *                        guidance for judging whether the worker
 *                        satisfied the brief, posting a follow-up when it
 *                        hasn't, and relaying up to the parent when the
 *                        worker needs the user or the work is done.
 */
enum SkillSource derives RW {
  case Mode
  case Discovery
  case User
  case Role(name: String)
  case Supervisor
}
