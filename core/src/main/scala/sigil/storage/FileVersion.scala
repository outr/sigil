package sigil.storage

import fabric.rw.*
import lightdb.time.Timestamp

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Verification token for safe-edit operations. `hash` is the
 * authoritative compare-and-set key — `writeIfMatch` succeeds only
 * when the storage backend's current `hash` matches the one the
 * caller saw at read time. `modified` is diagnostic (apps display
 * "last modified" without re-querying).
 *
 * Hash is SHA-256 over the bytes, hex-encoded. Storage backends
 * with native conditional-write semantics (S3 ETag) populate
 * `hash` from the backend's own version stamp instead — callers
 * compare strings, the underlying meaning differs by backend.
 */
case class FileVersion(hash: String, modified: Timestamp) derives RW

object FileVersion {

  /** Compute the canonical SHA-256 hash for a byte payload. Used by
    * filesystem-backed providers; backends with native version
    * stamps (S3 ETag) construct [[FileVersion]] from the backend's
    * own value. */
  def hashOf(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(b => f"$b%02x").mkString
  }

  /** Convenience for hashing a UTF-8 string. */
  def hashOf(text: String): String = hashOf(text.getBytes(StandardCharsets.UTF_8))

  /** Compute a fresh [[FileVersion]] over the given bytes, stamped
    * with the current wall clock. */
  def of(bytes: Array[Byte]): FileVersion =
    FileVersion(hashOf(bytes), Timestamp())
}
