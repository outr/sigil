package sigil.conversation

import fabric.rw.*

/**
 * Classifies the provenance of an active skill slot on
 * [[ParticipantContext.activeSkills]]. Multiple sources can contribute
 * simultaneously — a participant can have a `Mode`-injected skill AND a
 * `Discovery`-promoted skill active at the same time — without
 * overwriting each other, because the map is keyed by `SkillSource`.
 *
 *   - `Mode`      — injected by the current operating mode (e.g.,
 *                   switching to `Coding` pulls in coding-specific
 *                   context)
 *   - `Discovery` — promoted via `find_capability` / capability search
 *   - `User`      — explicit user directive ("use this skill")
 */
enum SkillSource derives RW {
  case Mode
  case Discovery
  case User
}
