package sigil.tool.provider

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Filter for the model-catalog listing.
 *
 *   - `provider` — exact-match (case-insensitive) on the model's
 *     provider segment ("openai", "anthropic", "local", …). `None`
 *     returns models from every provider.
 *   - `query` — substring match (case-insensitive) against the
 *     model's id, name, and description. Useful for "find all the
 *     gpt-5 variants" without knowing exact ids.
 *   - `limit` — cap on results returned. `None` returns the whole
 *     filtered set (the agent typically pages by re-querying with a
 *     narrower filter).
 */
case class ListModelsInput(provider: Option[String] = None,
                           query: Option[String] = None,
                           limit: Option[Int] = None)
  extends ToolInput derives RW
