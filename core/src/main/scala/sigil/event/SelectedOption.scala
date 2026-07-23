package sigil.event

import fabric.rw.*

/**
   * One option from a `respond_options` block the user picked. Carried
   * inside [[OptionSelection]] on a [[Message]] when the user's reply
   * originated as a selection rather than free-typed text.
   */
case class SelectedOption(value: String,
                          label: String,
                          description: Option[String] = None) derives RW
