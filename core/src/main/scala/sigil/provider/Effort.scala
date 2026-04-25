package sigil.provider

import fabric.rw.*

/**
 * How much reasoning / thinking effort the model should apply.
 * Providers translate this to their wire-format knob:
 *  - Anthropic: `thinking.budget_tokens` (must be >= 1024 and < max_tokens)
 *  - OpenAI Responses: `reasoning.effort` (low|medium|high)
 *  - Google Gemini 2.5: `thinkingConfig.thinkingBudget` (int; -1 dynamic; 0 off)
 *  - DeepSeek reasoner: no-op (reasoner model auto-thinks)
 *  - llama.cpp: no-op (no reasoning knob in the chat-completions surface)
 *
 * `None` in `GenerationSettings.effort` means "thinking off" for providers
 * whose models default to thinking (notably Google 2.5). That preserves
 * predictable tool-call behavior under tight token budgets; callers who
 * want thinking must opt in explicitly.
 */
enum Effort derives RW {
  case Low
  case Medium
  case High
  case Max
  case Custom(tokens: Int) // explicit token budget for providers that honor it
}

object Effort {
  /** Anthropic requires budget_tokens >= 1024 and strictly less than max_tokens.
    * We reserve a minimum of 512 tokens for actual output. */
  def anthropicBudgetTokens(effort: Effort, maxTokens: Int): Int = {
    val raw = effort match {
      case Low         => 1024
      case Medium      => 4096
      case High        => 16384
      case Max         => maxTokens - 512
      case Custom(n)   => n
    }
    val ceiling = math.max(1024, maxTokens - 512)
    math.max(1024, math.min(raw, ceiling))
  }

  def googleThinkingBudget(effort: Effort): Int = effort match {
    case Low       => 1024
    case Medium    => 8192
    case High      => 24576
    case Max       => -1 // dynamic — model decides
    case Custom(n) => n
  }

  /** Returns the OpenAI Responses API `reasoning.effort` level.
    * `Custom` is mapped to `"high"` because the Responses API exposes a
    * coarse level rather than a token budget. */
  def openAIEffortLevel(effort: Effort): String = effort match {
    case Low       => "low"
    case Medium    => "medium"
    case High      => "high"
    case Max       => "high"
    case Custom(_) => "high"
  }
}
