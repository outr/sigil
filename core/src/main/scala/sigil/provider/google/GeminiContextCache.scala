package sigil.provider.google

import lightdb.time.Timestamp

import java.security.MessageDigest
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * In-process registry of Gemini explicit `cachedContents` resources,
 * keyed by a stable-prefix hash.
 *
 * Gemini context caching is resource-oriented: the stable prefix of a
 * request (system instruction + tool-schema block) is POSTed once to
 * `cachedContents.create`, which returns a resource name; subsequent
 * `generateContent` requests reference that name via the
 * `cachedContent` field and omit the now-cached content inline. The
 * cached prefix is billed at a steep discount.
 *
 * This class owns only the lookup table and its expiry bookkeeping —
 * the create / reference HTTP mechanics live in
 * [[GoogleProvider]]. The map is keyed by a content hash so any two
 * requests whose stable prefix is byte-identical share one resource;
 * turn-to-turn variation in the conversation tail never reaches this
 * key because the framework's `renderSystem` ordering places the
 * volatile sections last and only the stable head is hashed.
 *
 * Entries carry an `expiresAt` derived from the TTL passed to
 * `cachedContents.create`. [[lookup]] treats an entry whose expiry has
 * passed (allowing for a small safety margin) as absent so a stale
 * resource name is never sent on the wire — Gemini would reject it.
 * Concurrency-safe: the backing map is a [[TrieMap]], so concurrent
 * turns against the same provider instance race cleanly on
 * [[store]] / [[lookup]].
 */
final class GeminiContextCache {

  private val entries: TrieMap[GeminiCacheKey, GeminiCachedPrefix] = TrieMap.empty

  /**
   * Resolve a live cached-content resource for the given prefix hash.
   * Returns `None` when no entry exists or the existing entry is at
   * or past its expiry (minus [[GeminiContextCache.expiryMargin]]).
   * An expired entry is evicted as a side effect so the map does not
   * accumulate dead resource names.
   */
  def lookup(key: GeminiCacheKey, now: Timestamp = Timestamp()): Option[GeminiCachedPrefix] =
    entries.get(key).flatMap { entry =>
      if (entry.isLive(now)) Some(entry)
      else {
        entries.remove(key, entry)
        None
      }
    }

  /**
   * Record a freshly created cached-content resource. The `expiresAt`
   * is computed from `createdAt + ttl`. Overwrites any prior entry
   * for the same key (a re-create after expiry).
   */
  def store(key: GeminiCacheKey, resourceName: String, ttl: FiniteDuration, createdAt: Timestamp = Timestamp()): GeminiCachedPrefix = {
    val entry = GeminiCachedPrefix(
      resourceName = resourceName,
      expiresAt = Timestamp(createdAt.value + ttl.toMillis)
    )
    entries.update(key, entry)
    entry
  }

  /**
   * Drop the entry for a key — used when Gemini rejects a referenced
   * resource (e.g. it was evicted server-side before its client-side
   * TTL elapsed) so the next turn re-creates rather than re-sending a
   * dead name.
   */
  def invalidate(key: GeminiCacheKey): Unit = entries.remove(key)

  /**
   * Current number of tracked entries — exposed for tests and
   * diagnostics.
   */
  def size: Int = entries.size
}

object GeminiContextCache {

  /**
   * Safety margin subtracted from an entry's expiry when judging
   * liveness. A resource that expires within this window is treated
   * as already gone so an in-flight request does not race the
   * server-side eviction.
   */
  val expiryMargin: FiniteDuration = 30.seconds

  /**
   * Record-separator joining the system instruction and tool-schema
   * block before hashing. A control character keeps distinct
   * (system, tools) pairs from colliding on a shared boundary.
   */
  private val PrefixSeparator: String = "\u001e"

  /**
   * Compute the stable-prefix hash for a system instruction and a
   * rendered tool-schema block.
   */
  def hashOf(systemInstruction: String, toolSchemaBlock: String): GeminiCacheKey = {
    val digest = MessageDigest.getInstance("SHA-256")
    val payload = systemInstruction + PrefixSeparator + toolSchemaBlock
    val bytes = digest.digest(payload.getBytes("UTF-8"))
    GeminiCacheKey(bytes.iterator.map(b => f"${b & 0xff}%02x").mkString)
  }
}
