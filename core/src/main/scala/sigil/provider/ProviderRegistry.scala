package sigil.provider

import lightdb.id.Id
import sigil.db.Model

/**
 * A [[ModelResolver]] over several providers. Dispatches a model id to
 * the member whose [[Provider.providerKey]] matches the id's provider
 * namespace (the segment before the first `/`), then returns that member
 * paired with the registered [[Model]].
 *
 * Because the namespace IS the dispatch key — and every provider both
 * registers its catalog under that key and renders its wire `model` field
 * by stripping it — a namespaced id can only ever reach the provider that
 * serves it. The model/provider mismatch class behind sigil #333 (a
 * `cloudflare/…` id sent through a provider whose namespace is something
 * else) is impossible here by construction.
 *
 * Not a [[Provider]] itself, deliberately: `Provider.apply` is `final`
 * and owns the per-provider pre-flight / tokenizer / rate-limit / retry
 * pipeline. A registry that extended `Provider` could only override the
 * `call` leaf, so every dispatched call would run that pipeline with the
 * registry's defaults instead of the resolved member's. Keeping the
 * registry a plain resolver means the real member's `apply` runs intact.
 *
 * Distinct from [[LoadBalancedProvider]], which pools *equivalent*
 * providers (same tier, round-robin failover); a registry routes across
 * *different* providers by identity. They compose — a registry member can
 * itself be a `LoadBalancedProvider`.
 */
final class ProviderRegistry(val providers: List[Provider]) extends ModelResolver {

  private val byKey: Map[String, Provider] =
    providers.iterator.map(p => p.providerKey -> p).toMap

  /**
   * Provider keys this registry can serve — surfaced in
   * resolution-miss diagnostics.
   */
  def knownKeys: List[String] = providers.map(_.providerKey)

  override def resolve(modelId: Id[Model]): Option[ProviderModel] = {
    val namespace = modelId.value.split("/", 2) match {
      case Array(ns, _) => ns
      case _ => modelId.value
    }
    byKey.get(namespace).flatMap(_.resolve(modelId))
  }
}
