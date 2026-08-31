package sigil.provider

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import sigil.provider.cache.{CachedResponse, CacheMode, CacheStore, MissingCacheException, RequestCacheKey}
import sigil.service.{Service, ServiceKind, ServiceState}
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import spice.http.HttpRequest

/**
 * Wraps any [[Provider]] with a request-keyed response cache. The
 * first call with a given canonical [[ProviderCall]] hits `underlying`,
 * captures the full event stream, and writes it to `cacheStore`.
 * Subsequent calls with the same canonical key replay from cache
 * without touching the network.
 *
 * Use in end-to-end tests that want real model output without per-run
 * cost or non-determinism. The wrapper sits BELOW the framework's
 * retry, pre-flight, and capacity layers — fresh recordings inherit
 * the full production code path, so cached fixtures reflect what the
 * orchestrator would have seen against the live upstream.
 *
 * Three modes (see [[CacheMode]]):
 *   - `RecordOrReplay` — read cache; on miss, record from underlying.
 *   - `ReplayOnly` — read cache only; raise [[MissingCacheException]] on miss.
 *   - `RecordAlways` — ignore cache; always record from underlying.
 *
 * Failed streams are NOT cached. If `underlying.call` errors mid-stream
 * the partial buffer is discarded and the cache file is never written,
 * so the next run hits the live upstream again rather than replaying
 * a frozen failure.
 */
final class CachedProvider(underlying: Provider,
                           sigilRef: Sigil,
                           cacheStore: CacheStore,
                           mode: CacheMode = CacheMode.RecordOrReplay)
  extends Provider {

  override def `type`: ProviderType = underlying.`type`

  override def providerKey: String = underlying.providerKey

  override protected def sigil: Sigil = sigilRef

  override def models: List[Model] = underlying.models

  /**
   * Local heuristic tokenizer — under-counts chat-template bytes by
   * single-digit percent vs. a backend-exact tokenizer, but lets the
   * pre-flight budget gate run without a network round-trip on cache
   * replays (the primary use case). Underlying providers whose
   * tokenizer hits the wire (e.g. `LlamaCppTokenizer` POSTing to
   * `/tokenize`) would defeat the "no network on replay" guarantee
   * otherwise.
   */
  override def tokenizer: Tokenizer = HeuristicTokenizer

  /**
   * The cache key must hash the same dialected schema the underlying
   * provider ships, so replay and live recordings agree.
   */
  override def schemaDialect: SchemaDialect = underlying.schemaDialect

  override def id: Id[Service] = underlying.id

  override def name: String = underlying.name

  override def kind: ServiceKind = underlying.kind

  override def currentState: ServiceState = underlying.currentState

  /**
   * Read a cached response, treating an unreadable entry (a fixture
   * recorded under an older serialization of [[ProviderEvent]], a
   * truncated file) as a miss rather than a failure. A stale cache
   * must never poison a live call — in `RecordOrReplay` the miss
   * falls through to a fresh recording that overwrites the stale
   * file; in `ReplayOnly` it surfaces as the standard
   * [[MissingCacheException]] pointing at re-recording.
   */
  private def readCache(keyHash: String): Option[CachedResponse] =
    try cacheStore.read(keyHash)
    catch {
      case t: Throwable =>
        scribe.warn(
          s"CachedProvider: cached response $keyHash is unreadable (${t.getClass.getSimpleName}: ${t.getMessage}) — " +
            "treating as a cache miss; the entry likely predates a ProviderEvent serialization change and needs re-recording")
        None
    }

  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    val keyHash = RequestCacheKey.canonical(
      input,
      schemaDialect,
      t => ToolDescriptionRenderer.render(t, input.currentMode, sigilRef)
    ).sha256
    mode match {
      case CacheMode.ReplayOnly =>
        readCache(keyHash) match {
          case Some(cached) =>
            scribe.debug(s"CachedProvider(replay-only): hit $keyHash (${cached.events.size} events)")
            Stream.emits(cached.events)
          case None =>
            Stream.force(Task.error(new MissingCacheException(
              s"No cached response for request hash $keyHash (mode = ReplayOnly). " +
                s"To record, run with ${CacheMode.EnvName}=record."
            )))
        }

      case CacheMode.RecordOrReplay =>
        readCache(keyHash) match {
          case Some(cached) =>
            scribe.debug(s"CachedProvider(record-or-replay): hit $keyHash (${cached.events.size} events)")
            Stream.emits(cached.events)
          case None =>
            scribe.debug(s"CachedProvider(record-or-replay): miss $keyHash — recording")
            recordAndCache(input, keyHash)
        }

      case CacheMode.RecordAlways =>
        scribe.debug(s"CachedProvider(record-always): recording $keyHash")
        recordAndCache(input, keyHash)
    }
  }

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    underlying.httpRequestFor(input)

  /**
   * Drain the underlying call into a buffered list, write to cache on
   * clean completion, and re-emit. Errors propagate without writing
   * — a failed recording must not become a frozen replay.
   */
  private def recordAndCache(input: ProviderCall, keyHash: String): Stream[ProviderEvent] =
    Stream.force(
      underlying.call(input).toList.map { events =>
        scribe.debug(s"CachedProvider: writing $keyHash (${events.size} events)")
        cacheStore.write(
          keyHash,
          CachedResponse(
            requestHash = keyHash,
            recordedAt = Timestamp(),
            events = events
          ))
        Stream.emits(events)
      }.handleError { t =>
        scribe.debug(s"CachedProvider: not writing $keyHash — underlying stream errored: ${t.getMessage}")
        Task.error(t)
      }
    )
}

object CachedProvider {

  /**
   * Convenience builder that takes the `Sigil` reference from the
   * caller and falls back to the same construction shape every
   * downstream test uses.
   */
  def apply(underlying: Provider,
            sigilRef: Sigil,
            cacheStore: CacheStore,
            mode: CacheMode = CacheMode.fromEnv): CachedProvider =
    new CachedProvider(underlying, sigilRef, cacheStore, mode)
}
