package sigil.storage

import rapid.Task

/**
 * Backend-agnostic byte store. Implementations: [[LocalFileStorageProvider]],
 * [[S3StorageProvider]].
 *
 * The framework always proxies bytes through its HTTP layer
 * ([[sigil.storage.http.StorageRouteFilter]]). Implementations expose
 * raw byte access; consumers never see the backend's native URL —
 * `Sigil.storageUrl(file)` returns a `sigil://storage/<id>` URI (or
 * whatever the app overrides to) which the route filter resolves
 * back through this provider. This keeps access control in one place
 * and means switching backends doesn't change consumer URLs.
 *
 * `path` is opaque to the provider — Sigil derives it as
 * `<spaceTag>/<id>` so listings stay tenant-scoped on backends that
 * support hierarchical listing, but providers MUST treat it as an
 * opaque key.
 *
 * **Safe-edit (compare-and-set) contract.** [[read]] returns a
 * [[StorageContents]] carrying the bytes plus a [[FileVersion]]
 * verification token. [[writeIfMatch]] commits only if the storage's
 * current version still matches the supplied `expected` — otherwise
 * it returns [[WriteResult.Stale]] with the freshest snapshot. The
 * default trait implementations are correct in intent but NOT
 * lock-safe (two concurrent callers can both observe a matching
 * version then both write). Concrete providers SHOULD override
 * `writeIfMatch` to use their backend's native conditional-write
 * (S3 If-Match) or an in-process lock keyed on the path. Apps that
 * don't care about staleness keep using the unconditional [[upload]].
 */
trait StorageProvider {

  /** Write bytes for the given path. Returns the canonical path the
    * provider stored them at — typically the input `path`, but
    * implementations may normalize. */
  def upload(path: String, data: Array[Byte], contentType: String): Task[String]

  /** Read bytes for the given path. `None` if the path doesn't exist. */
  def download(path: String): Task[Option[Array[Byte]]]

  def delete(path: String): Task[Unit]

  def exists(path: String): Task[Boolean]

  /** Read the current contents and verification token. Default
    * implementation hashes the downloaded bytes; backends with
    * native version stamps (S3 ETag) override to use the backend
    * value directly. */
  def read(path: String): Task[Option[StorageContents]] =
    download(path).map(_.map(bytes => StorageContents(bytes, FileVersion.of(bytes))))

  /** Conditional write — commit `data` iff the storage's current
    * version still matches `expected`. Default implementation does
    * a read-then-write that is NOT race-safe across concurrent
    * callers; concrete providers should override using a backend
    * lock or native CAS. */
  def writeIfMatch(path: String,
                   data: Array[Byte],
                   contentType: String,
                   expected: FileVersion): Task[WriteResult] =
    read(path).flatMap {
      case None => Task.pure(WriteResult.NotFound)
      case Some(current) if current.version.hash != expected.hash =>
        Task.pure(WriteResult.Stale(current))
      case Some(_) =>
        upload(path, data, contentType).map(_ => WriteResult.Written(FileVersion.of(data)))
    }
}
