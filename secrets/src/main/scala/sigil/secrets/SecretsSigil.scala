package sigil.secrets

import fabric.rw.RW
import sigil.Sigil
import sigil.db.SigilDB
import sigil.pipeline.InboundTransform
import sigil.signal.Signal

/**
 * Sigil refinement for apps that include the `sigil-secrets` module.
 * Constrains `type DB` to a subclass of [[SigilDB]] that mixes in
 * [[SecretsCollections]] (so `db.secrets` is available), and
 * surfaces a [[SecretStore]] hook backed by the module's default
 * [[DatabaseSecretStore]] implementation.
 *
 * Apps extend this trait alongside any other module-Sigil
 * refinements they need:
 *
 * {{{
 *   class MyAppDB(...) extends SigilDB(...) with SecretsCollections with McpCollections
 *
 *   class MyAppSigil extends SecretsSigil with McpSigil {
 *     type DB = MyAppDB
 *     protected def buildDB(d, sm, u) = new MyAppDB(d, sm, u)
 *   }
 * }}}
 *
 * The `secretStore` hook resolves to a [[DatabaseSecretStore]] using
 * the standard `<dbPath>/crypto.key` symmetric-key location. Apps
 * that need a managed-secrets backend (Vault, KMS, GCP Secret
 * Manager) override [[secretStore]] with their own implementation.
 */
trait SecretsSigil extends Sigil {
  type DB <: SigilDB & SecretsCollections

  /**
   * Server-side store for secrets — encrypted (retrievable) tokens
   * and hashed (verify-only) passwords. See [[SecretStore]] for the
   * contract.
   *
   * Default: [[DatabaseSecretStore.default]] — Argon2 hashing +
   * AES/CBC/PKCS5 encryption (via `com.outr.scalapass`), persisted
   * via lightdb to `db.secrets`, with a symmetric key read from (or
   * auto-generated at) `<dbPath>/crypto.key`.
   */
  def secretStore: SecretStore = defaultSecretStore

  private final lazy val defaultSecretStore: SecretStore =
    DatabaseSecretStore.default(this)

  /**
   * The secrets module registers [[SecretSubmission]] for
   * polymorphic Signal RW — the wire transport needs to deserialize
   * incoming `SecretSubmission` frames from the user's UI.
   * Subclasses adding their own custom signal types should
   * concatenate with this list.
   */
  override protected def signalRegistrations: List[RW[? <: Signal]] =
    List(summon[RW[SecretSubmission]])

  /**
   * Prepended [[SecretCaptureTransform]] — runs before any other
   * inbound transform so the secret value is stripped and replaced
   * with a `SecretRef`-bearing Message before downstream stages see
   * it. Subclasses overriding `inboundTransforms` should call
   * `super.inboundTransforms` to preserve this behavior.
   */
  override def inboundTransforms: List[InboundTransform] =
    new SecretCaptureTransform(secretStore) :: super.inboundTransforms
}

