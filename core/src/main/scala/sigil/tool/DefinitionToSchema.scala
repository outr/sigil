package sigil.tool

import fabric.Json
import fabric.define.Definition

/**
 * Façade over [[WireSurface]]'s schema emission — preserved for
 * out-of-repo provider integrations that already import this object.
 *
 * Converts a fabric [[Definition]] into strict JSON Schema suitable for LLM
 * structured output and tool calling.
 *
 * Polymorphic types (sealed traits / enums with data) are emitted as discriminated
 * `oneOf` branches using a `"type"` const discriminator. Simple string enums
 * (all case objects) are emitted as `{type: "string", enum: [...]}`.
 *
 * Objects are strict (`additionalProperties: false`); required fields are computed
 * from non-Opt members.
 *
 * Output is standard JSON Schema. Provider-specific dialects (e.g. OpenAI's strict
 * mode requiring every property in `required` with nullable unions for optional
 * fields) should post-process this output.
 */
object DefinitionToSchema {

  val Discriminator: String = WireSurface.Discriminator

  /** Emit canonical JSON Schema for a `Definition`. Delegates to
    * [[WireSurface.emitSchema]]; one walker, one set of conventions. */
  def apply(definition: Definition): Json = WireSurface.emitSchema(definition)

  /** True if the schema tree rooted at `definition` contains a
    * `DefType.Json` anywhere. Used by `OpenAIProvider` to decide whether a
    * tool can ship with `strict: true`: OpenAI's strict mode demands
    * every "object" branch carry closed `properties` +
    * `additionalProperties: false`, which is mutually exclusive with
    * "any JSON value" — strict and `Json` can't coexist, so any tool
    * whose input contains a `Json` field opts out of strict per-tool. */
  def containsJson(definition: Definition): Boolean = WireSurface.containsJson(definition)
}
