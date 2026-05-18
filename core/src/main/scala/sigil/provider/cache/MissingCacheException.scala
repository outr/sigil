package sigil.provider.cache

/**
 * Raised when a [[CachedProvider]] in [[CacheMode.ReplayOnly]] is
 * asked to serve a request whose cache key has no recorded fixture.
 *
 * The default CI workflow runs tests with `CACHE_MODE=replay` so the
 * absence of a fixture is a build-time failure: either the developer
 * forgot to commit the fixture after recording, or the request shape
 * drifted (prompt edit, tool roster change, new memory) and needs
 * a fresh recording via `CACHE_MODE=record`.
 */
final class MissingCacheException(message: String) extends RuntimeException(message)
