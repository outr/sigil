package sigil.provider

import fabric.rw.*

/**
 * Provider-managed tools — capabilities the provider executes
 * server-side without a client round-trip, in contrast to framework
 * [[sigil.tool.Tool]]s where the client defines the schema and
 * executes the result locally.
 *
 * Apps opt into these per request via
 * [[ConversationRequest.builtInTools]] / [[OneShotRequest.builtInTools]].
 * Providers that support a given built-in translate it to their wire
 * format (`{"type": "web_search"}` for OpenAI Responses,
 * `{"type": "web_search_20241210"}` for Anthropic, etc.); providers
 * that don't support it silently drop the opt-in (rather than
 * failing — apps composing multiple providers don't want to branch
 * per-provider at the call site).
 *
 * Variants mirror OpenAI's Responses API built-in tools, since that's
 * the richest set in common use. Other providers implement the subset
 * they support.
 */
enum BuiltInTool derives RW {
  /** Model autonomously searches the web, consumes results, and
    * integrates them into its output. */
  case WebSearch

  /** Model autonomously generates images as part of its output. The
    * generated image appears as a [[sigil.tool.model.ResponseContent.Image]]
    * block in the final Message. */
  case ImageGeneration

  /** Model autonomously searches uploaded files / a vector store. */
  case FileSearch

  /** Model autonomously executes code in a sandbox. */
  case CodeInterpreter

  /** Model autonomously drives a virtual computer (mouse, keyboard,
    * screenshots). */
  case ComputerUse
}
