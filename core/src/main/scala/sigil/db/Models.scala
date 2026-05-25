package sigil.db

import fabric.rw.*
import lightdb.time.Timestamp

/**
 * Persisted snapshot of the framework's known [[Model]] catalog
 * (sigil #277). Stored as a `lightdb.StoredValue` slot on [[SigilDB]]
 * so the catalog survives process restart and the in-memory
 * [[sigil.cache.ModelRegistry]] can be seeded on boot without a
 * network call.
 *
 * `Sigil.instance` reads this slot at startup, seeds the in-memory
 * registry from `list`, and refreshes from OpenRouter when `list` is
 * empty OR `refreshed` is older than `Sigil.modelRefreshInterval`
 * (default 8 hours). Each successful refresh writes the slot back with
 * `refreshed = Timestamp()` and `list = freshly-fetched catalog`.
 *
 * The default value (`list = Nil, refreshed = Timestamp()`) is a
 * never-refreshed marker. The empty `list` is the load-bearing signal
 * — a fresh-stamped but empty catalog still triggers a blocking
 * refresh at boot so a brand-new install doesn't tip into the
 * `UnregisteredModelException` path on its first call.
 *
 * @param list      the catalog itself — every `Model` OpenRouter
 *                  returned on the last successful refresh.
 * @param refreshed timestamp of the last successful refresh
 *                  (`Timestamp()` for the never-refreshed default).
 */
case class Models(list: List[Model] = Nil,
                  refreshed: Timestamp = Timestamp()) derives RW
