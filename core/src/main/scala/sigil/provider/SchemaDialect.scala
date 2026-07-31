package sigil.provider

import fabric.Json
import sigil.tool.{Tool, WireSurface}

/**
 * A provider's JSON-schema dialect for tool-call arguments — the single
 * seam between the canonical schema ([[sigil.tool.WireSurface.schema]])
 * and what actually ships on the wire.
 *
 * Every provider declares exactly one dialect. The dialected result is
 * the shared object consumed by the wire request renderer, the token
 * estimator ([[Provider.estimateToolBytes]]), the request-cache key
 * ([[sigil.provider.cache.RequestCacheKey]]), and the refusal body
 * ([[sigil.tool.RefusalPayload]]). One derivation, no call site can
 * re-derive it differently.
 *
 * The open-JSON opt-out lives INSIDE the dialect: a tool whose input
 * contains a `DefType.Json` field cannot take a closed-object strict
 * shape, so [[OpenAIStrict.transform]] branches on `containsOpenJson`
 * itself — callers pass the flag, they never choose the transform.
 */
trait SchemaDialect {

  /** Stable dialect discriminator (diagnostics, cache-key diffing). */
  def name: String

  /** Rewrite the canonical schema into this dialect's wire shape.
    * `containsOpenJson` is true when the schema tree contains a
    * `DefType.Json` anywhere — dialects that widen to a closed-object
    * strict form fall back to their lenient shape in that case. */
  def transform(canonical: Json, containsOpenJson: Boolean): Json

  /** Whether this dialect engages the wire-level `strict` flag for a
    * schema with / without open JSON. Only grammar-constraining strict
    * dialects return `true`. */
  def strictFor(containsOpenJson: Boolean): Boolean = false

  /** The dialected wire schema for `tool` — THE shared object every
    * downstream consumer reads. */
  final def apply(tool: Tool): Json =
    transform(tool.wireSurface.schema, WireSurface.containsJson(tool.inputDefinition))

  /** Whether the wire `strict` flag engages for `tool` under this
    * dialect. */
  final def strictForTool(tool: Tool): Boolean =
    strictFor(WireSurface.containsJson(tool.inputDefinition))
}

object SchemaDialect {

  /** OpenAI `strict: true` grammar-constrained decoding: every property
    * required (optionals widened to nullable), `additionalProperties:
    * false` everywhere, grammar-incompatible keywords stripped,
    * `oneOf` converted to `anyOf`. Schemas containing a `DefType.Json`
    * field are mutually exclusive with the closed-object requirement
    * and fall back to [[StrictSchema.stripUnsupportedKeys]] with the
    * strict flag off. Declared by OpenAI, DeepSeek, DeepInfra,
    * DigitalOcean, Cloudflare, and OpenRouter. */
  case object OpenAIStrict extends SchemaDialect {
    val name: String = "openai-strict"
    def transform(canonical: Json, containsOpenJson: Boolean): Json =
      if (containsOpenJson) StrictSchema.stripUnsupportedKeys(canonical)
      else StrictSchema.forOpenAIStrict(canonical)
    override def strictFor(containsOpenJson: Boolean): Boolean = !containsOpenJson
  }

  /** Gemini function calling — natively grammar-constrained, no strict
    * flag, but the validator rejects `additionalProperties`, `const`,
    * and the grammar-only keywords. */
  case object Gemini extends SchemaDialect {
    val name: String = "gemini"
    def transform(canonical: Json, containsOpenJson: Boolean): Json =
      StrictSchema.forGemini(canonical)
  }

  /** Anthropic — no grammar constraint; grammar-only keywords are
    * stripped for schema hygiene and the post-decode
    * [[sigil.tool.ToolInputValidator]] is the real safety net. Also
    * the conservative fallback for chat-completions backends without
    * strict-mode support. */
  case object Anthropic extends SchemaDialect {
    val name: String = "anthropic"
    def transform(canonical: Json, containsOpenJson: Boolean): Json =
      StrictSchema.stripUnsupportedKeys(canonical)
  }

  /** The canonical schema verbatim — llama.cpp translates the full
    * schema (including `pattern` / `format` / numeric bounds) into a
    * GBNF grammar at generation time. */
  case object Identity extends SchemaDialect {
    val name: String = "identity"
    def transform(canonical: Json, containsOpenJson: Boolean): Json = canonical
  }
}
