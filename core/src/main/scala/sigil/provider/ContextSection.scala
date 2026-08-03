package sigil.provider

import sigil.conversation.TurnInput
import sigil.diagnostics.ProfileSection

/**
 * One section of the rendered system prompt, as data.
 *
 * The ordered list [[sigil.Sigil.contextSections]] is the single source
 * of truth for the prompt's section taxonomy. Four consumers read it: the renderer (`Provider.renderSystem`) concatenates `rendered`
 * results per [[Placement]]; [[sigil.diagnostics.RequestProfiler]]
 * counts tokens per section from the same `rendered` function; the
 * curator's budget cascade runs `shed` in `shedStage` order; and
 * `context_breakdown` reads the profiler's output.
 *
 * A section that declares a `shedStage` MUST carry the matching `shed`
 * effect — a stage the curator cannot act on is a silent no-op that
 * makes the cascade look deeper than it is. [[ContextSections.shedCascade]]
 * rejects the pair at construction.
 *
 * `budget` is per-section shaping, not shedding: it caps what this one
 * section may spend regardless of how much room the turn has, and it
 * only bites on [[SectionBody.Entries]] (see [[SectionBody]] for why
 * prose is exempt). It composes with the prompt shape's entry caps —
 * both apply, the cap bounding entry COUNT and the budget bounding
 * rendered SIZE — and with the per-model budget the running
 * [[PromptShape]] declares, the tighter of the two winning.
 *
 * @param id        diagnostics discriminator, one per section
 * @param placement stable prefix vs volatile tail
 * @param shedStage position in the curator's section-shed cascade —
 *                  lower sheds first; `None` never sheds
 * @param render    the section's body, or `None` when it contributes
 *                  nothing for this turn
 * @param shed      how the curator drops this section's contribution
 *                  from a turn; required when `shedStage` is set
 * @param budget    tokens this section may occupy; `None` is unbounded
 */
case class ContextSection(id: ProfileSection,
                          placement: Placement,
                          shedStage: Option[Int],
                          render: SectionContext => Option[SectionBody],
                          shed: Option[TurnInput => TurnInput] = None,
                          budget: Option[Int] = None) {

  /** The section's text for this turn, trimmed to its effective budget.
    * Every consumer reads THIS rather than `render`, so the renderer and
    * the profiler count the same bytes under a budget too. */
  def rendered(c: SectionContext): Option[String] =
    render(c).flatMap { body =>
      effectiveBudget(c.promptShape).fold(Option(body))(body.within(_)).map(_.rendered)
    }

  /** The tighter of the section's own budget and the one the running
    * model's prompt shape declares for it. */
  def effectiveBudget(shape: PromptShape): Option[Int] =
    (budget.toList ++ shape.budgetFor(id).toList).minOption
}
