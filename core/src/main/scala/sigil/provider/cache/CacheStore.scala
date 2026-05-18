package sigil.provider.cache

/**
 * Backing store for [[CachedProvider]] fixtures. Reads return
 * `None` when no fixture exists; writes overwrite any prior
 * recording for the same key.
 *
 * The default implementation is [[FileSystemCacheStore]] (one JSONL
 * file per key hash). Test-only stores (in-memory maps) implement the
 * same surface so unit specs can verify recording / replay semantics
 * without touching disk.
 */
trait CacheStore {
  def read(keyHash: String): Option[CachedResponse]
  def write(keyHash: String, response: CachedResponse): Unit
}
