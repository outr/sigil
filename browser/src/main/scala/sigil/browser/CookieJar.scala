package sigil.browser

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.SpaceId

/**
 * Persisted, encrypted-at-rest collection of browser cookies for a
 * given [[SpaceId]] tenant scope. Stored on
 * `SigilDB.cookieJars` (added by [[BrowserCollections]]).
 *
 *   - **`space`** is the tenant boundary. A user-scoped jar
 *     (`UserSpace(alice)`) survives across alice's conversations; a
 *     project-scoped jar (`ProjectSpace(p)`) is shared by everyone
 *     in the project. Single-assignment rule applies — copy the
 *     record to expose under another space.
 *
 *   - **`encryptedCookies`** is the AES-encrypted JSON serialization
 *     of `List[BrowserCookie]`. The framework's
 *     [[sigil.secrets.SecretStore]] decrypts on read and encrypts on
 *     write so plaintext cookies never touch the database. Apps
 *     mixing in [[BrowserSigil]] must also mix in
 *     [[sigil.secrets.SecretsSigil]] (enforced via the type-bound on
 *     `BrowserSigil.DB`).
 *
 *   - **`metadata`** carries app-level annotations — typically the
 *     human-readable label apps render in a "manage saved logins"
 *     UI ("youtube.com / alice") plus the participant id that
 *     created it.
 *
 * The id is opaque so jars can be referenced by
 * `BrowserScript.cookieJarId` for resume.
 */
case class CookieJar(space: SpaceId,
                     encryptedData: String = "",
                     encryptedSalt: String = "",
                     metadata: Map[String, String] = Map.empty,
                     created: Timestamp = Timestamp(),
                     modified: Timestamp = Timestamp(),
                     _id: Id[CookieJar] = CookieJar.id())
  extends RecordDocument[CookieJar]

object CookieJar extends RecordDocumentModel[CookieJar] with JsonConversion[CookieJar] {
  implicit override def rw: RW[CookieJar] = RW.gen

  override def id(value: String = Unique()): Id[CookieJar] = Id(value)

  /** Tenant-key index — string form of `SpaceId.value`. */
  val spaceKey: I[String] = field.index("spaceKey", _.space.value)
  val createdAt: I[Long] = field.index("createdAt", _.created.value)
}
