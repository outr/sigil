package sigil.conversation

import fabric.rw.*

/**
 * Classifies the provenance of an active skill slot on
 * [[ParticipantProjection.activeSkills]]. Multiple sources can contribute
 * simultaneously — a participant can have a `Mode`-injected skill AND a
 * `Discovery`-promoted skill active at the same time — without
 * overwriting each other, because the map is keyed by `SkillSource`.
 *
 *   - `Mode`            — injected by the current operating mode
 *                         (e.g., switching to `Coding` pulls in
 *                         coding-specific context)
 *   - `Discovery`       — promoted via `find_capability` / capability
 *                         search
 *   - `User`            — explicit user directive ("use this skill")
 *   - `Behavior(name)`  — contributed by an
 *                         [[sigil.behavior.Behavior]] on the
 *                         participant's `behaviors` list. Parameterized
 *                         by behavior name so multiple behaviors don't
 *                         clobber each other in the map.
 */
enum SkillSource derives RW {
  case Mode
  case Discovery
  case User
  case Behavior(name: String)
}
