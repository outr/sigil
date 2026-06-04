package sigil.provider.cache

import fabric.rw.*

/**
 * Operating mode for [[CachedProvider]] — controls whether a request
 * may hit the wrapped provider, replay from the on-disk cache, or
 * both.
 *
 *   - [[RecordOrReplay]] — read from cache when present; otherwise
 *     forward to the underlying provider and persist its response for
 *     future replays. Default for local development.
 *   - [[ReplayOnly]] — read from cache only; raise
 *     [[MissingCacheException]] when no fixture exists. For CI runs
 *     that must never reach out to a live model.
 *   - [[RecordAlways]] — bypass the cache entirely; forward to the
 *     underlying provider and overwrite any prior fixture. For
 *     re-recording after prompt / tool roster changes.
 *
 * Modes derive [[fabric.rw.RW]] so they round-trip cleanly when used
 * inside other persisted configuration shapes.
 */
enum CacheMode derives RW {
  case RecordOrReplay
  case ReplayOnly
  case RecordAlways
}

object CacheMode {

  /**
   * Environment-variable name that overrides the cache mode at
   * runtime. Case-insensitive; unrecognised values fall back to
   * [[RecordOrReplay]].
   */
  val EnvName: String = "CACHE_MODE"

  /**
   * Resolve the mode from the [[EnvName]] environment variable.
   * Accepted values (case-insensitive):
   *
   *   - `replay` -> [[ReplayOnly]]
   *   - `record` -> [[RecordAlways]]
   *   - any other / unset -> [[RecordOrReplay]]
   */
  def fromEnv: CacheMode = sys.env.get(EnvName).map(_.trim.toLowerCase) match {
    case Some("replay") => ReplayOnly
    case Some("record") => RecordAlways
    case _ => RecordOrReplay
  }
}
