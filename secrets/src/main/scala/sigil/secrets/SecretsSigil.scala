package sigil.secrets

import fabric.rw.*
import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB
import sigil.participant.ParticipantId
import sigil.pipeline.InboundTransform
import sigil.event.Event
import sigil.signal.Notice

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

  /** [[SecretSubmission]] is the secrets module's only durable Event. */
  override protected def eventRegistrations: List[RW[? <: Event]] =
    List(summon[RW[SecretSubmission]])

  /**
   * The secrets module's Notice request/reply pairs — registered into
   * the `Notice` slice so wire-transport routers, Dart codegen, and
   * spice's `durableSubtypes` knob can correctly classify them as
   * transient (ephemeral, never replayed) rather than durable Events.
   */
  override protected def noticeRegistrations: List[RW[? <: Notice]] = List(
    summon[RW[RequestSecret]],
    summon[RW[SecretSnapshot]],
    summon[RW[RequestSecretVerify]],
    summon[RW[SecretVerifyResult]],
    summon[RW[RequestSecretSet]],
    summon[RW[SecretSetResult]],
    summon[RW[RequestSecretDelete]],
    summon[RW[SecretDeleteResult]]
  )

  /**
   * Prepended [[SecretCaptureTransform]] — runs before any other
   * inbound transform so the secret value is stripped and replaced
   * with a `SecretRef`-bearing Message before downstream stages see
   * it. Subclasses overriding `inboundTransforms` should call
   * `super.inboundTransforms` to preserve this behavior.
   */
  override def inboundTransforms: List[InboundTransform] =
    new SecretCaptureTransform(secretStore) :: super.inboundTransforms

  /**
   * Dispatch the secrets module's Notice subtypes through
   * [[secretStore]]. Apps that override should match their own
   * subtypes first, then call `super.handleNotice(notice, fromViewer)`
   * so the secrets-handler arms still run.
   */
  override def handleNotice(notice: Notice, fromViewer: ParticipantId): Task[Unit] = notice match {
    case RequestSecret(secretId) =>
      secretStore.get[String](secretId).flatMap { value =>
        publishTo(fromViewer, SecretSnapshot(secretId, value))
      }
    case RequestSecretVerify(secretId, candidate) =>
      secretStore.verify[String](secretId, candidate).flatMap { matched =>
        publishTo(fromViewer, SecretVerifyResult(secretId, matched))
      }
    case RequestSecretSet(secretId, value, kind) =>
      val store: Task[Unit] = kind match {
        case sigil.security.SecretKind.Encrypted => secretStore.setEncrypted[String](secretId, value)
        case sigil.security.SecretKind.Hashed    => secretStore.setHashed[String](secretId, value)
      }
      store.attempt.flatMap { result =>
        publishTo(fromViewer, SecretSetResult(secretId, result.isSuccess))
      }
    case RequestSecretDelete(secretId) =>
      secretStore.delete(secretId).attempt.flatMap { result =>
        publishTo(fromViewer, SecretDeleteResult(secretId, result.isSuccess))
      }
    case _ => super.handleNotice(notice, fromViewer)
  }
}

