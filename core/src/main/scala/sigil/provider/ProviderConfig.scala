package sigil.provider

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.SpaceId
import spice.net.URL

/**
 * Persisted provider connection — credentials + endpoint for a
 * single provider, scoped to a [[SpaceId]] for tenant visibility.
 * One config per API key; gives access to every model the
 * provider exposes.
 *
 *   - **`space`** — tenant scope. Records are filtered by
 *     `accessibleSpaces` on read; the single-assignment rule means
 *     one record carries one space (copy to expose under another).
 *   - **`apiKeySecretId`** — pointer into the framework's
 *     [[sigil.Sigil.resolveApiKey]] hook (typically resolved via
 *     `SecretsSigil`'s `secretStore`). Apps without secrets-module
 *     wiring leave this `None` and supply the key through env /
 *     other means; the framework does not store plaintext keys
 *     under any code path.
 *   - **`baseUrl`** — optional endpoint override (self-hosted
 *     proxies, on-prem OpenAI-compatible gateways, etc.). `None`
 *     uses the provider type's default endpoint.
 *   - **`isDefault`** — convenience marker apps use to pick "the
 *     account" when there are multiple under the same space (e.g.
 *     personal vs work API key for the same provider). The
 *     framework doesn't enforce uniqueness — apps own the policy.
 *   - **`metadata`** — app-level annotations (label color, billing
 *     tag, account name, etc.).
 */
case class ProviderConfig(space: SpaceId,
                          label: String,
                          providerType: ProviderType,
                          apiKeySecretId: Option[String] = None,
                          baseUrl: Option[URL] = None,
                          isDefault: Boolean = false,
                          metadata: Map[String, String] = Map.empty,
                          created: Timestamp = Timestamp(),
                          modified: Timestamp = Timestamp(),
                          _id: Id[ProviderConfig] = ProviderConfig.id())
  extends RecordDocument[ProviderConfig]

object ProviderConfig extends RecordDocumentModel[ProviderConfig] with JsonConversion[ProviderConfig] {
  implicit override def rw: RW[ProviderConfig] = RW.gen

  override def id(value: String = Unique()): Id[ProviderConfig] = Id(value)

  /** Tenant-key index — string form of `SpaceId.value`. */
  val spaceKey: I[String] = field.index("spaceKey", _.space.value)

  /** Provider-type index — for "list every OpenAI config in this space". */
  val providerTypeKey: I[String] = field.index("providerTypeKey", _.providerType.toString)
}
