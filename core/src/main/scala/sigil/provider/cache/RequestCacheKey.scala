package sigil.provider.cache

import fabric.*
import fabric.io.JsonFormatter
import fabric.rw.*
import sigil.provider.{
  BuiltInTool,
  Effort,
  GenerationSettings,
  MessageContent,
  Mode,
  ProviderCall,
  ProviderMessage,
  ReasoningMode,
  ToolChoice
}
import sigil.tool.{DefinitionToSchema, Tool}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Canonical, deterministic form of a [[ProviderCall]] for cache-keying.
 *
 * Every field that would change how the model responds participates in
 * the key. Identifiers minted per call (tool-call ids on assistant
 * messages, server-side response handles, retry context) are stripped
 * — they vary across runs but carry no semantic content. The
 * resulting JSON is serialized via [[fabric.Json.toKey]], which writes
 * object fields in sorted-key order recursively, so two requests with
 * the same logical payload hash to the same bytes regardless of field
 * emission order.
 *
 * IN the key: model id, system prompt, full message contents (text,
 * images, reasoning summaries), tool roster (sorted by name; full
 * name + description + JSON schema), built-in tool flags (sorted),
 * tool-choice mode, generation settings, current mode discriminator.
 *
 * NOT in the key: per-call uuids (tool-call ids, message ids),
 * wall-clock timestamps, OpenAI Responses' server-side
 * `previous_response_id` handle, retry-context bookkeeping.
 */
case class RequestCacheKey(canonical: Json) {

  /** SHA-256 of the canonical JSON bytes, hex-encoded. Used as the
    * cache file name. */
  def sha256: String = {
    val bytes = canonical.toKey.getBytes(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(b => f"$b%02x").mkString
  }

  /** Compact JSON rendering of the canonical key — useful for diffing
    * two near-identical requests when a cache miss is unexpected. */
  def asCompactJson: String = JsonFormatter.Compact(canonical)
}

object RequestCacheKey {

  /** Canonicalize a [[ProviderCall]] into a deterministic JSON shape
    * suitable for hashing. */
  def canonical(call: ProviderCall): RequestCacheKey = {
    val payload = obj(
      "model"              -> str(stripProviderPrefix(call.modelId.value)),
      "system"             -> str(call.systemCombined),
      "messages"           -> arr(call.messages.toList.map(canonicalizeMessage)*),
      "tools"              -> arr(call.tools.sortBy(_.name.value).toList.map(canonicalizeTool)*),
      "builtInTools"       -> arr(call.builtInTools.toList.map(_.toString).sorted.map(str)*),
      "toolChoice"         -> canonicalizeToolChoice(call.toolChoice),
      "generationSettings" -> canonicalizeGenerationSettings(call.generationSettings),
      "currentMode"        -> str(call.currentMode.name)
    )
    RequestCacheKey(payload)
  }

  private def canonicalizeMessage(message: ProviderMessage): Json = message match {
    case ProviderMessage.System(content) =>
      obj(
        "kind"    -> str("system"),
        "content" -> str(content)
      )
    case ProviderMessage.User(content) =>
      obj(
        "kind"   -> str("user"),
        "blocks" -> arr(content.toList.map(canonicalizeContent)*)
      )
    case ProviderMessage.Assistant(content, toolCalls) =>
      obj(
        "kind"      -> str("assistant"),
        "content"   -> str(content),
        // Tool-call ids are minted per dispatch and don't affect what
        // the model generates next — replace with positional index so
        // semantically identical assistant turns collide cleanly.
        "toolCalls" -> arr(toolCalls.zipWithIndex.map { case (tc, index) =>
          obj(
            "index"    -> num(index),
            "name"     -> str(tc.name),
            "argsJson" -> canonicalizeArgsJson(tc.argsJson)
          )
        }*)
      )
    case ProviderMessage.ToolResult(_, content) =>
      // toolCallId is the per-dispatch uuid that paired this result
      // with its assistant call. The pairing is positional in the
      // canonical form (this ToolResult follows the assistant message
      // that minted it), so the literal id is omitted from the key.
      obj(
        "kind"    -> str("toolResult"),
        "content" -> str(content)
      )
    case ProviderMessage.Reasoning(_, summary, encryptedContent) =>
      // providerItemId is a per-call handle; the reasoning's actual
      // content (summary + encrypted CoT blob) is what affects the
      // model's next-turn behaviour.
      obj(
        "kind"             -> str("reasoning"),
        "summary"          -> arr(summary.map(str)*),
        "encryptedContent" -> encryptedContent.map(str).getOrElse(Null)
      )
  }

  private def canonicalizeContent(content: MessageContent): Json = content match {
    case MessageContent.Text(text) =>
      obj(
        "kind" -> str("text"),
        "text" -> str(text)
      )
    case MessageContent.Image(url, altText) =>
      obj(
        "kind"    -> str("image"),
        "url"     -> str(url.toString),
        "altText" -> altText.map(str).getOrElse(Null)
      )
    case MessageContent.ImageBytes(mediaType, base64, altText) =>
      obj(
        "kind"      -> str("imageBytes"),
        "mediaType" -> str(mediaType),
        "base64"    -> str(base64),
        "altText"   -> altText.map(str).getOrElse(Null)
      )
  }

  private def canonicalizeTool(tool: Tool): Json = obj(
    "name"        -> str(tool.name.value),
    "description" -> str(tool.description),
    "schema"      -> DefinitionToSchema(tool.inputDefinition)
  )

  private def canonicalizeToolChoice(choice: ToolChoice): Json = choice match {
    case ToolChoice.None              => obj("kind" -> str("none"))
    case ToolChoice.Auto              => obj("kind" -> str("auto"))
    case ToolChoice.Required          => obj("kind" -> str("required"))
    case ToolChoice.Specific(toolName) =>
      obj(
        "kind" -> str("specific"),
        "name" -> str(toolName.value)
      )
  }

  private def canonicalizeGenerationSettings(settings: GenerationSettings): Json = obj(
    "temperature"     -> settings.temperature.map(num).getOrElse(Null),
    // Sigil #276 — canonicalize via the typed `effectiveCap` so the
    // cache key folds the deprecated `maxOutputTokens` and the new
    // `outputTokenCap` to one shape. `ModelMax` keys as `null` so an
    // unspecified cap doesn't drift the key away from the pre-#276
    // `None` shape that already canonicalised to `null`.
    "maxOutputTokens" -> settings.explicitWireMaxTokens.map(n => num(n)).getOrElse(Null),
    "effort"          -> settings.effort.map(canonicalizeEffort).getOrElse(Null),
    "topP"            -> settings.topP.map(num).getOrElse(Null),
    "stopSequences"   -> arr(settings.stopSequences.toList.map(str)*),
    "reasoningMode"   -> str(settings.reasoningMode.toString)
  )

  private def canonicalizeEffort(effort: Effort): Json = effort match {
    case Effort.Low           => obj("kind" -> str("low"))
    case Effort.Medium        => obj("kind" -> str("medium"))
    case Effort.High          => obj("kind" -> str("high"))
    case Effort.Max           => obj("kind" -> str("max"))
    case Effort.Custom(tokens) => obj("kind" -> str("custom"), "tokens" -> num(tokens))
  }

  /** Parse the tool-call argsJson back into a [[Json]] when possible so
    * field order doesn't perturb the cache key. Falls back to the raw
    * string when the argsJson isn't well-formed JSON (degenerate
    * model output occasionally lands as a partial fragment). */
  private def canonicalizeArgsJson(argsJson: String): Json =
    try fabric.io.JsonParser(argsJson)
    catch { case _: Throwable => str(argsJson) }

  /** Drop any leading `<providerKey>/` namespace from a model id so the
    * cache key represents the model semantically — the same model
    * served by two providers (or by a live provider vs a replay stub)
    * hashes identically. The live provider's model registry adds the
    * namespace on resolution; the replay stub does no I/O, so its
    * model ids stay bare. Stripping at the cache boundary papers over
    * the asymmetry. */
  private def stripProviderPrefix(modelId: String): String = {
    val slash = modelId.indexOf('/')
    if (slash > 0 && slash < modelId.length - 1) modelId.substring(slash + 1)
    else modelId
  }
}
