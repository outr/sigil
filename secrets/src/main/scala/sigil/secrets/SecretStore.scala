package sigil.secrets

import sigil.security.SecretKind
import fabric.rw.RW
import lightdb.id.Id
import rapid.Task

import scala.concurrent.duration.FiniteDuration

/**
 * Server-side pairing for the [[sigil.tool.model.ResponseContent.SecretInput]]
 * UI primitive (and the standalone API for tools / apps that need to
 * manage secrets directly).
 *
 * Two storage modes (see [[SecretKind]]):
 *
 *   - **Encrypted** secrets — round-trippable via [[get]]. The value
 *     is JSON-rendered (via the supplied `RW[T]`) and the JSON string
 *     is encrypted at rest with the app's symmetric key. Suitable for
 *     anything a tool needs to read back as a typed value: API tokens
 *     (`String`), OAuth credentials (a multi-field case class), arbitrary
 *     structured config records.
 *   - **Hashed** secrets — verify-only via [[verify]]. The value is
 *     JSON-rendered (via the supplied `RW[T]`) and the rendered string
 *     is what's hashed at rest (Argon2 by default). Only
 *     equality-against-a-candidate is supported — the candidate is
 *     rendered the same way and the resulting hashes are compared.
 *     Suitable for user passwords (`T = String`) and for sealed
 *     multi-field credentials hashed as a unit (e.g. recovery-answer
 *     tuples). Note: hashing `String` produces a hash of the JSON form
 *     `"hunter2"` (quotes included), not the raw bytes — apps that need
 *     to interop with an external password DB whose hashes are over the
 *     raw bytes should call Argon2 directly rather than going through
 *     this trait.
 *
 * Type discipline at the call site: the type used at `setEncrypted` MUST
 * match the type used at `get` for the same id. There is no stored type
 * tag — fabric's marshaling is type-erased, so a write/read mismatch
 * surfaces as a JSON-parse failure or garbage-typed value at the read
 * site. Apps that need cross-version evolution own their own JSON
 * versioning convention.
 *
 * Sigil ships [[DatabaseSecretStore]] as the default — Argon2 for hashed
 * entries, AES/CBC/PKCS5Padding for encrypted entries, persisted via
 * lightdb to the app's `SigilDB.secrets` collection. Apps that need a
 * managed-secrets backend (Vault, AWS KMS, GCP Secret Manager, etc.)
 * implement this trait themselves and override
 * [[sigil.Sigil.secretStore]].
 */
trait SecretStore {

  /** Store an encrypted (retrievable) value. The value is JSON-rendered
    * via `RW[T]` and the rendered string is what's actually encrypted at
    * rest. Overwrites any existing entry at the same id, regardless of
    * kind. */
  def setEncrypted[T: RW](id: Id[SecretRecord], value: T,
                          ttl: Option[FiniteDuration] = None): Task[Unit]

  /** Store a hashed (verify-only) value. The value is JSON-rendered via
    * `RW[T]` and the rendered string is what's actually hashed at rest.
    * Overwrites any existing entry at the same id, regardless of kind. */
  def setHashed[T: RW](id: Id[SecretRecord], value: T,
                       ttl: Option[FiniteDuration] = None): Task[Unit]

  /** Decrypt and parse the typed value at `id`. Returns `None` for
    * hashed entries, missing entries, entries past their TTL, or
    * entries whose stored JSON doesn't parse into `T`. */
  def get[T: RW](id: Id[SecretRecord]): Task[Option[T]]

  /** Compare `candidate` to a hashed secret. The candidate is rendered
    * the same way the stored value was rendered (`JsonFormatter.Compact`
    * of `RW[T].read(candidate)`) and the resulting hash is compared.
    * `false` for encrypted entries, missing entries, or entries past
    * their TTL. */
  def verify[T: RW](id: Id[SecretRecord], candidate: T): Task[Boolean]

  /** Remove the entry at this id. Idempotent — no-op if missing. Used
    * during revoke / reset / password-change flows. */
  def delete(id: Id[SecretRecord]): Task[Unit]
}
