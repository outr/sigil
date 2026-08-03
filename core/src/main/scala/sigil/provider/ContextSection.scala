package sigil.provider

import sigil.conversation.TurnInput
import sigil.diagnostics.ProfileSection

/**
 * One section of the rendered system prompt, as data.
 *
 * The ordered list [[sigil.Sigil.contextSections]] is the single source
 * of truth for the prompt's section taxonomy. Four consumers read it: the renderer (`Provider.renderSystem`) concatenates `render`
 * results per [[Placement]]; [[sigil.diagnostics.RequestProfiler]]
 * counts tokens per section from the same `render` functions; the
 * curator's budget cascade runs `shed` in `shedStage` order; and
 * `context_breakdown` reads the profiler's output.
 *
 * A section that declares a `shedStage` MUST carry the matching `shed`
 * effect — a stage the curator cannot act on is a silent no-op that
 * makes the cascade look deeper than it is. [[ContextSections.shedCascade]]
 * rejects the pair at construction.
 *
 * @param id        diagnostics discriminator, one per section
 * @param placement stable prefix vs volatile tail
 * @param shedStage position in the curator's section-shed cascade —
 *                  lower sheds first; `None` never sheds
 * @param render    the section's text, or `None` when it contributes
 *                  nothing for this turn
 * @param shed      how the curator drops this section's contribution
 *                  from a turn; required when `shedStage` is set
 */
case class ContextSection(id: ProfileSection,
                          placement: Placement,
                          shedStage: Option[Int],
                          render: SectionContext => Option[String],
                          shed: Option[TurnInput => TurnInput] = None)
