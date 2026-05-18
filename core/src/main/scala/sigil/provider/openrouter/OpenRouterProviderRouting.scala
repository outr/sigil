package sigil.provider.openrouter

import fabric.*

/**
 * OpenRouter's per-request `provider` routing block. Maps to the
 * documented knobs at
 * `https://openrouter.ai/docs/guides/routing/provider-selection`:
 *
 *   - `order` — preferred provider slugs to try in order
 *   - `only` — exclusive allow-list of provider slugs
 *   - `ignore` — deny-list of provider slugs
 *   - `allow_fallbacks` — when `false`, fail immediately if the
 *     primary provider can't serve the request (default `true`)
 *   - `data_collection` — `"allow"` or `"deny"`; restricts to
 *     providers honouring the requested data-retention policy
 *   - `zdr` — restrict to Zero-Data-Retention endpoints
 *   - `require_parameters` — only route to providers that support
 *     every requested generation parameter (temperature, top_p, …)
 *
 * Every field is `Option`-typed — `None` omits the corresponding
 * JSON key entirely. OpenRouter applies its defaults when a knob is
 * absent.
 *
 * The framework's [[OpenRouterProvider]] defaults to
 * [[noChineseHosting]] which populates `ignore` with
 * [[OpenRouter.ChineseHostedSlugs]] — a deny-list guaranteeing
 * outbound traffic doesn't route to mainland-China-hosted upstreams.
 * Apps with different policy override `OpenRouterProvider.providerRouting`
 * with their own value.
 */
case class OpenRouterProviderRouting(order: Option[List[String]] = None,
                                     only: Option[List[String]] = None,
                                     ignore: Option[List[String]] = None,
                                     allowFallbacks: Option[Boolean] = None,
                                     dataCollection: Option[String] = None,
                                     zdr: Option[Boolean] = None,
                                     requireParameters: Option[Boolean] = None) {

  /**
   * Render as the JSON object OpenRouter expects under the
   * top-level `provider` request field. Empty when every knob is
   * `None` (no constraint emitted).
   */
  def toJson: Json = {
    val fields = Vector.newBuilder[(String, Json)]
    order.foreach(o => fields += "order" -> arr(o.map(str)*))
    only.foreach(o => fields += "only" -> arr(o.map(str)*))
    ignore.foreach(i => fields += "ignore" -> arr(i.map(str)*))
    allowFallbacks.foreach(b => fields += "allow_fallbacks" -> bool(b))
    dataCollection.foreach(s => fields += "data_collection" -> str(s))
    zdr.foreach(b => fields += "zdr" -> bool(b))
    requireParameters.foreach(b => fields += "require_parameters" -> bool(b))
    obj(fields.result()*)
  }
}

object OpenRouterProviderRouting {

  /**
   * Geographic-restriction default: deny routing to any
   * mainland-China-hosted OpenRouter provider slug. See
   * [[OpenRouter.ChineseHostedSlugs]] for the exact deny-list.
   */
  val noChineseHosting: OpenRouterProviderRouting =
    OpenRouterProviderRouting(ignore = Some(OpenRouter.ChineseHostedSlugs.toList.sorted))
}
