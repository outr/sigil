package spec

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.db.Model
import sigil.provider.cache.{CacheMode, FileSystemCacheStore}
import sigil.provider.{
  CachedProvider, ProviderCall, ProviderEvent, ProviderType, Provider
}
import sigil.service.{Service, ServiceKind, ServiceState}
import spice.http.HttpRequest

/**
 * Shared helper for live-LLM specs migrated to the [[CachedProvider]]
 * VCR pattern. Wraps a live provider in a [[CachedProvider]] whose
 * fixtures live under `core/src/test/resources/provider-cache/<suite>/`.
 *
 * The fixture directory is committed to source control. Cache mode is
 * read from the `CACHE_MODE` environment variable (`record` |
 * `replay` | unset = `RecordOrReplay`).
 *
 * In [[CacheMode.ReplayOnly]] the underlying provider is never
 * constructed — every cache lookup either hits a fixture or raises
 * [[sigil.provider.cache.MissingCacheException]] — so CI runs need no
 * network access to the upstream backend.
 */
object CachedProviderFixtures {

  /** Compute the on-disk cache root for the named suite. */
  def cacheRoot(suiteName: String): java.nio.file.Path =
    java.nio.file.Path.of("core", "src", "test", "resources", "provider-cache", suiteName)

  /** Wrap an underlying-provider task with a [[CachedProvider]] keyed
    * by the calling suite's class name. The wrapped task is `singleton`
    * so the cached provider is reused across the suite's tests.
    *
    * Convenience for the standard [[TestSigil]]-backed suites.
    * Suites that own their own Sigil instance (workflow specs, etc.)
    * call [[wrapFor]] instead. */
  def wrap(suite: AnyRef, underlying: Task[Provider]): Task[Provider] =
    wrapFor(suite.getClass.getSimpleName.replace("$", ""), underlying, TestSigil)

  /** Wrap with an explicit suite name + Sigil reference. For suites
    * driving a non-[[TestSigil]] instance (e.g. [[TestWorkflowSigil]])
    * or when the caller wants to share fixtures across two related
    * suites by passing a stable name. */
  def wrapFor(suiteName: String, underlying: Task[Provider], sigilRef: _root_.sigil.Sigil): Task[Provider] = {
    val safeName = suiteName.replace("$", "")
    val root = cacheRoot(safeName)
    val store = new FileSystemCacheStore(root)
    val mode = CacheMode.fromEnv
    val effectiveUnderlying: Task[Provider] = mode match {
      case CacheMode.ReplayOnly =>
        // Never touch the live upstream in replay-only mode — even
        // constructing the underlying provider hits the network for
        // some implementations (`LlamaCppProvider.apply` does
        // `/v1/models` + `/props`). The replay-only stub raises if
        // it's ever asked to call (which means the cache key drifted
        // from what the fixtures cover).
        Task.pure(new ReplayOnlyStubProvider(sigilRef): Provider)
      case _ =>
        underlying
    }
    effectiveUnderlying.map(p => new CachedProvider(p, sigilRef, store, mode): Provider).singleton
  }

  /** Stub provider used as the `underlying` for [[CachedProvider]] in
    * [[CacheMode.ReplayOnly]]. Constructing this does no I/O. Every
    * method that would actually require a wire call throws — the
    * [[CachedProvider]] satisfies all reads from the on-disk cache or
    * surfaces a [[sigil.provider.cache.MissingCacheException]]. */
  private final class ReplayOnlyStubProvider(sigilRef: _root_.sigil.Sigil) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def providerKey: String = "cached-replay-stub"
    override protected def sigil: _root_.sigil.Sigil = sigilRef
    override def models: List[Model] = Nil
    override def id: Id[Service] = Id[Service]("provider.cached-replay-stub")
    override def name: String = "CachedReplayStub"
    override def kind: ServiceKind = ServiceKind.ModelServer
    override def currentState: ServiceState = ServiceState.Up
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.force(Task.error(new RuntimeException(
        "CachedProviderFixtures ReplayOnlyStubProvider.call invoked — this means the cache key drifted " +
          "from the recorded fixture. Re-record with CACHE_MODE=record against a reachable backend."
      )))
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("ReplayOnlyStubProvider.httpRequestFor not supported"))
  }
}
