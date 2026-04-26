package sigil.secrets

import sigil.security.SecretKind
import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique

/**
 * Persisted form of a secret. The id is the [[SecretId]]'s `value`
 * (so apps can address records by their natural id). The payload
 * carries either:
 *
 *   - `encryptedData` + `encryptedSalt` (base64-encoded) — for
 *     [[SecretKind.Encrypted]] entries, recomposed into a scalapass
 *     `Encrypted(data, salt)` at decryption time.
 *   - `hashedValue` (Argon2 hash string) — for [[SecretKind.Hashed]]
 *     entries.
 *
 * The encrypted form is stored as base64 strings rather than scalapass's
 * `Encrypted` type so the persisted RW is decoupled from scalapass's
 * fabric version (the lib's bundled RW implementations target an older
 * fabric API; storing as primitives sidesteps that drift).
 *
 * `expiresAt` is the wall-clock millis after which the entry is
 * stale. `None` means no expiry. Expiry is enforced lazily on read by
 * [[DatabaseSecretStore]] — stale rows return `None` / `false` and
 * are replaced on the next write; no background sweeper runs. Apps
 * that want eager expiry can run a janitor against the `secrets`
 * collection.
 */
case class SecretRecord(kind: SecretKind,
                        encryptedData: Option[String] = None,
                        encryptedSalt: Option[String] = None,
                        hashedValue: Option[String] = None,
                        expiresAt: Option[Long] = None,
                        created: Timestamp = Timestamp(),
                        modified: Timestamp = Timestamp(),
                        _id: Id[SecretRecord] = SecretRecord.id())
  extends RecordDocument[SecretRecord]

object SecretRecord extends RecordDocumentModel[SecretRecord] with JsonConversion[SecretRecord] {
  implicit override def rw: RW[SecretRecord] = RW.gen

  override def id(value: String = Unique()): Id[SecretRecord] = Id(value)
}
