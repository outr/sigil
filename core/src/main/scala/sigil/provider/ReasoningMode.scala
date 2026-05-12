package sigil.provider

import fabric.rw.*

/**
 * Provider-agnostic toggle for reasoning-capable models'
 * chain-of-thought emission. Lets apps express intent
 * ("I want the fast non-thinking path" / "force deliberation
 * for this turn") once in [[GenerationSettings]]; each provider
 * translates to its own protocol — kimi's `/think` / `/no_think`
 * system-prompt directive, Anthropic's `thinking` block, OpenAI's
 * `reasoning.effort`, Google's `thinking_config`, etc.
 *
 * Apps that don't care leave the default `Auto`; providers fall
 * through to whatever the model's deployment ships with.
 *
 * Bug #155.
 */
enum ReasoningMode derives RW {
  /** Model / deployment default. Provider doesn't inject any
    * directive. */
  case Auto
  /** Force reasoning on. Kimi: appends `/think` to the system
    * prompt. Anthropic: `thinking: {type: "enabled", ...}` (when
    * the model supports it). */
  case On
  /** Force reasoning off. Kimi: appends `/no_think` to the system
    * prompt. Anthropic / OpenAI reasoning models: providers
    * silently ignore — those families don't expose a hard off
    * switch on every model. */
  case Off
}
