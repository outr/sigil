package sigil.provider

import fabric.rw.*

/** How much reasoning effort the model should apply.
 * The provider translates this to the appropriate wire format:
 * - Anthropic: thinking.budget_tokens (scaled from effort level)
 * - OpenAI: reasoning_effort (low/medium/high)
 * - Others: best effort or ignored */
enum Effort derives RW {
  case Low
  case Medium
  case High
  case Max
  case Custom(tokens: Int) // explicit token budget for providers that support it
}
