package sigil.tool.consult

import sigil.provider.{GenerationSettings, WorkType}

/**
 * Marker mixed into every framework-internal one-shot consult tool
 * (topic classification, memory extraction, summarization, rerank,
 * progress reflection, classify-memory). These tools are never on an
 * agent's roster — the framework drives them through
 * [[ConsultTool.invoke]] / [[ConsultTool.invokeRich]] with
 * `tool_choice = required`.
 *
 * The trait centralizes the two cost levers each consult would
 * otherwise hand-roll at every call site:
 *
 *   - [[consultWorkType]] — the [[WorkType]] the call should route
 *     through. Callers resolve a concrete model via
 *     `Sigil.routedModelFor(tool.consultWorkType, ...)` so a cheap
 *     classification / summarization tier handles the bookkeeping
 *     call instead of inheriting the agent's conversation-tier model.
 *
 *   - [[consultSettings]] — the canonical [[GenerationSettings]] for
 *     the consult's actual output shape: a tight `maxOutputTokens`
 *     sized to the structured payload (plus a reasoning-spill margin)
 *     and `ReasoningMode.Off` so framework bookkeeping spends zero
 *     reasoning tokens. [[ConsultTool.invoke]] derives its default
 *     `generationSettings` from this when the tool is a
 *     `FrameworkConsult`, so the tight cap applies without each call
 *     site repeating it.
 *
 * Consults whose output size is genuinely per-call (summarization —
 * the cap scales with the target summary length) leave
 * [[consultSettings]] as a conservative ceiling and let the caller
 * pass a computed override.
 */
trait FrameworkConsult {

  /** Work category for [[sigil.provider.ProviderStrategy]] routing —
    * callers resolve the concrete model from this rather than reusing
    * the agent's conversation-tier model. */
  def consultWorkType: WorkType

  /** Canonical generation settings for this consult: tight
    * `maxOutputTokens` for the structured payload, `ReasoningMode.Off`.
    * [[ConsultTool]] uses this as the default when no explicit
    * override is supplied. */
  def consultSettings: GenerationSettings
}
