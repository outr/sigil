package sigil.tool.model

import fabric.rw.*

/**
 * A single choice within a [[ResponseContent.Options]] block.
 *
 * @param label       human-readable display text
 * @param value       stable identifier returned when selected
 * @param description optional longer explanation of the choice
 * @param exclusive   when present in a multi-select (`allowMultiple = true`)
 *                    block, selecting this option clears any other selections
 *                    — typically used for a "None of the above" escape hatch.
 */
case class SelectOption(label: String,
                        value: String,
                        description: Option[String] = None,
                        exclusive: Boolean = false) derives RW
