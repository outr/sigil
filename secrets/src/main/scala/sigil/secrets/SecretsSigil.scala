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
 * Apps must supply the symmetric encryption key via
 * [[secretStoreKey]] — losing it invalidates every encrypted record
 * in the database, so the framework refuses to silently generate one.
 * Apps that need a managed-secrets backend (Vault, KMS, GCP Secret
 * Manager) override [[secretStore]] with their own implementation
 * and ignore [[secretStoreKey]].
 */
trait SecretsSigil extends Sigil {
  type DB <: SigilDB & SecretsCollections

  /**
   * Symmetric AES key the framework's standard
   * [[DatabaseSecretStore]] uses to encrypt + decrypt secret records.
   * Apps source this from typed Profig config, an env var, KMS /
   * Vault, a mounted secret file, or wherever the deployment's
   * key-management story lives.
   *
   * No default — losing this key invalidates every encrypted record
   * in `db.secrets`, so the framework refuses to silently generate
   * one. Apps make a deliberate decision about key source.
   *
   * Patterns:
   *
   * {{{
   *   // From typed Profig config:
   *   override def secretStoreKey: String = App.config.crypto.key
   *
   *   // Direct Profig lookup:
   *   override def secretStoreKey: String =
   *     profig.Profig("myapp.crypto.key").as[String]
   *
   *   // Mounted secret file (Kubernetes secret, Docker secret, ...):
   *   override def secretStoreKey: String =
   *     java.nio.file.Files.readString(java.nio.file.Path.of("/run/secrets/sigil-crypto-key")).trim
   *
   *   // Opt-in to file-on-disk material with explicit generation
   *   // at deploy time:
   *   override def secretStoreKey: String = {
   *     val p = java.nio.file.Path.of(System.getenv("SIGIL_CRYPTO_KEY_PATH"))
   *     if (!java.nio.file.Files.exists(p)) sigil.secrets.DatabaseSecretStore.generateKeyFile(p)
   *     else java.nio.file.Files.readString(p).trim
   *   }
   * }}}
   *
   * Apps with rotation, per-tenant keys, or fancier policies
   * override [[secretStore]] directly with their own
   * [[SecretStore]] implementation.
   */
  def secretStoreKey: String

  /**
   * Server-side store for secrets — encrypted (retrievable) tokens
   * and hashed (verify-only) passwords. See [[SecretStore]] for the
   * contract.
   *
   * Default: a [[DatabaseSecretStore]] using [[secretStoreKey]] as
   * its symmetric AES material.
   */
  def secretStore: SecretStore = defaultSecretStore

  private final lazy val defaultSecretStore: SecretStore =
    new DatabaseSecretStore(this, secretStoreKey)

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

