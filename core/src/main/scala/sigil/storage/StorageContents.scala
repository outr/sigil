package sigil.storage

import java.nio.charset.StandardCharsets

/**
 * Bytes-plus-version snapshot returned by [[StorageProvider.read]].
 * The `version` is the verification token the caller passes back to
 * [[StorageProvider.writeIfMatch]] to guarantee no other writer has
 * modified the storage between read and write.
 *
 * Not derived RW — this carries raw bytes that aren't intended to
 * round-trip through fabric. Tools that need to surface contents on
 * the wire convert to UTF-8 text or base64 explicitly.
 */
final case class StorageContents(bytes: Array[Byte], version: FileVersion) {

  /** Decode the bytes as UTF-8 text. Throws on invalid sequences —
    * tools should only call this for content known to be text. */
  def asText: String = new String(bytes, StandardCharsets.UTF_8)
}
