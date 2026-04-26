package sigil.secret

import com.outr.scalapass.{Argon2PasswordFactory, PasswordFactory}
import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.RW
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.Sigil

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.spec.{IvParameterSpec, PBEKeySpec, SecretKeySpec}
import javax.crypto.{Cipher, SecretKeyFactory}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Default [[SecretStore]] — persists entries via the host
 * [[Sigil.withDB]] connection. Cryptographic primitives:
 *
 *   - **Hashed** entries — Argon2 via
 *     [[com.outr.scalapass.Argon2PasswordFactory]].
 *   - **Encrypted** entries — AES/CBC/PKCS5Padding with a key derived
 *     from the supplied symmetric secret via PBKDF2WithHmacSHA256.
 *     Each entry gets a fresh 8-byte random salt that's stored alongside
 *     the ciphertext (base64-encoded), so the same plaintext written
 *     twice produces different ciphertext records.
 *
 * Typed encrypted entries: `setEncrypted[T]` JSON-renders the value via
 * the supplied `RW[T]` and encrypts the rendered string. `get[T]`
 * decrypts, parses the JSON, and lifts back into `T`. The on-disk shape
 * is the same opaque base64 ciphertext regardless of `T`; type
 * information is not persisted.
 *
 * IV derivation: deterministic from the symmetric key (first 16 bytes
 * of `SHA-256(key)`), fixed across restarts so persisted records can
 * be decrypted after the JVM cycles. Per-record uniqueness comes from
 * the random salt that goes into PBKDF2's key derivation.
 *
 * Expiry is enforced lazily on read — a stale entry returns `None`
 * (for [[get]]) or `false` (for [[verify]]) and is replaced on the
 * next write at the same id. No background sweeper runs.
 *
 * Concurrency: lightdb's transaction isolation handles within-database
 * safety. Two writers racing the same id produce a deterministic
 * last-writer-wins outcome via `upsert`. Apps that want stricter
 * semantics layer that above this trait.
 */
final class DatabaseSecretStore(sigil: Sigil,
                                cryptoKey: String,
                                passwordFactory: PasswordFactory = Argon2PasswordFactory(),
                                clock: () => Long = () => System.currentTimeMillis()) extends SecretStore {
  private val keyChars: Array[Char] = cryptoKey.toCharArray
  private val ivSpec: IvParameterSpec = {
    val keyBytes = MessageDigest.getInstance("SHA-256").digest(cryptoKey.getBytes("UTF-8"))
    new IvParameterSpec(keyBytes.take(16))
  }
  private val secretFactory: SecretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
  private val random: SecureRandom = SecureRandom.getInstanceStrong

  private val IterationCount: Int = 65536
  private val KeyLengthBits: Int = 256
  private val SaltLengthBytes: Int = 8

  private def expiry(ttl: Option[FiniteDuration]): Option[Long] =
    ttl.map(d => clock() + d.toMillis)

  private def isStale(record: SecretRecord): Boolean =
    record.expiresAt.exists(_ <= clock())

  private def deriveKey(salt: Array[Byte]): SecretKeySpec = {
    val spec = new PBEKeySpec(keyChars, salt, IterationCount, KeyLengthBits)
    new SecretKeySpec(secretFactory.generateSecret(spec).getEncoded, "AES")
  }

  private def encryptString(plaintext: String): (String, String) = {
    val salt = new Array[Byte](SaltLengthBytes)
    random.nextBytes(salt)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), ivSpec)
    val ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"))
    (Base64.getEncoder.encodeToString(ciphertext), Base64.getEncoder.encodeToString(salt))
  }

  private def decryptString(dataB64: String, saltB64: String): String = {
    val ciphertext = Base64.getDecoder.decode(dataB64)
    val salt = Base64.getDecoder.decode(saltB64)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), ivSpec)
    new String(cipher.doFinal(ciphertext), "UTF-8")
  }

  override def setEncrypted[T: RW](id: Id[SecretRecord], value: T, ttl: Option[FiniteDuration]): Task[Unit] = Task {
    val rendered = JsonFormatter.Compact(summon[RW[T]].read(value))
    val (data, salt) = encryptString(rendered)
    SecretRecord(
      kind = SecretKind.Encrypted,
      encryptedData = Some(data),
      encryptedSalt = Some(salt),
      hashedValue = None,
      expiresAt = expiry(ttl),
      modified = Timestamp(),
      _id = id
    )
  }.flatMap(record => sigil.withDB(_.secrets.transaction(_.upsert(record)))).unit

  override def setHashed[T: RW](id: Id[SecretRecord], value: T, ttl: Option[FiniteDuration]): Task[Unit] = Task {
    val rendered = JsonFormatter.Compact(summon[RW[T]].read(value))
    val hash = passwordFactory.hash(rendered)
    SecretRecord(
      kind = SecretKind.Hashed,
      encryptedData = None,
      encryptedSalt = None,
      hashedValue = Some(hash),
      expiresAt = expiry(ttl),
      modified = Timestamp(),
      _id = id
    )
  }.flatMap(record => sigil.withDB(_.secrets.transaction(_.upsert(record)))).unit

  override def get[T: RW](id: Id[SecretRecord]): Task[Option[T]] =
    sigil.withDB(_.secrets.transaction(_.get(id))).map {
      case Some(record) if isStale(record) => None
      case Some(record) if record.kind == SecretKind.Encrypted =>
        for {
          data <- record.encryptedData
          salt <- record.encryptedSalt
          rendered = decryptString(data, salt)
          parsed <- Try(JsonParser(rendered)).toOption
          value <- Try(summon[RW[T]].write(parsed)).toOption
        } yield value
      case _ => None
    }

  override def verify[T: RW](id: Id[SecretRecord], candidate: T): Task[Boolean] =
    sigil.withDB(_.secrets.transaction(_.get(id))).map {
      case Some(record) if isStale(record) => false
      case Some(record) if record.kind == SecretKind.Hashed =>
        val rendered = JsonFormatter.Compact(summon[RW[T]].read(candidate))
        record.hashedValue.exists(passwordFactory.verify(rendered, _))
      case _ => false
    }

  override def delete(id: Id[SecretRecord]): Task[Unit] =
    sigil.withDB(_.secrets.transaction(_.delete(id))).unit
}

object DatabaseSecretStore {

  /**
   * Read or auto-generate a symmetric key at `path` and build a
   * [[DatabaseSecretStore]] backed by it. On first run the file is
   * created with a fresh random key (combined `Unique`-derived strings
   * — high entropy, stable across restarts because it's persisted to
   * disk).
   *
   * Apps that want a different key-management strategy (HSM, KMS,
   * env-var-injected) construct [[DatabaseSecretStore]] directly with
   * the key string they obtain however they like.
   */
  def fromKeyFile(sigil: Sigil, keyPath: java.nio.file.Path): DatabaseSecretStore = {
    val key = readOrGenerateKey(keyPath)
    new DatabaseSecretStore(sigil, key)
  }

  /** Default key path — `<dbPath>/crypto.key` if `dbPath` is set,
    * otherwise `data/sigil/crypto.key` relative to the working dir. */
  def defaultKeyPath(sigil: Sigil): java.nio.file.Path = {
    val dbPathStr = profig.Profig("sigil.dbPath").opt[String](using fabric.rw.stringRW).getOrElse("data/sigil")
    java.nio.file.Path.of(dbPathStr, "crypto.key")
  }

  /** Default factory — uses [[defaultKeyPath]] to locate / create the
    * symmetric key. Apps that want a different policy override
    * [[sigil.Sigil.secretStore]] with a hand-built instance. */
  def default(sigil: Sigil): DatabaseSecretStore =
    fromKeyFile(sigil, defaultKeyPath(sigil))

  private def readOrGenerateKey(path: java.nio.file.Path): String = {
    if (java.nio.file.Files.exists(path)) {
      java.nio.file.Files.readString(path).trim
    } else {
      Option(path.getParent).foreach(java.nio.file.Files.createDirectories(_))
      val generated = rapid.Unique.sync() + "-" + rapid.Unique.sync()
      java.nio.file.Files.writeString(path, generated)
      try {
        java.nio.file.Files.setPosixFilePermissions(
          path,
          java.util.Set.of(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
          )
        )
      } catch { case _: UnsupportedOperationException => () } // non-POSIX FS — best-effort
      scribe.info(s"DatabaseSecretStore: generated new symmetric key at $path")
      generated
    }
  }
}
