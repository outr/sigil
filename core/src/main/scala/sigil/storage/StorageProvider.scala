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
}
