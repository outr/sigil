package sigil.provider

import sigil.diagnostics.ProfileSection

/**
 * One section of the rendered system prompt, as data.
 *
 * The ordered list in [[ContextSections.all]] is the single source of
 * truth for the prompt's section taxonomy. Four consumers read it:
 * the renderer (`Provider.renderSystem`) concatenates `render` results
 * per [[Placement]]; [[sigil.diagnostics.RequestProfiler]] counts
 * tokens per section from the same `render` functions; the curator's
 * budget cascade drives its section-shaped shed stages from
 * `shedStage`; and `context_breakdown` reads the profiler's output.
 *
 * @param id        diagnostics discriminator, one per section
 * @param placement stable prefix vs volatile tail
 * @param shedStage position in the curator's section-shed cascade —
 *                  lower sheds first; `None` never sheds
 * @param render    the section's text, or `None` when it contributes
 *                  nothing for this turn
 */
case class ContextSection(id: ProfileSection,
                          placement: Placement,
                          shedStage: Option[Int],
                          render: SectionContext => Option[String])
