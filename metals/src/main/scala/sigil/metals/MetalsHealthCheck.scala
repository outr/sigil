package sigil.metals

import java.nio.file.{Files, Path}

object MetalsHealthCheck {

  /**
   * Reconcile a possibly-stale Metals H2 db before spawning a
   * fresh Metals process. When `hasLivePeer = false` the file is
   * removed; when true it's left alone (the live peer owns its
   * own state). No-op when the file doesn't exist (first-ever
   * spawn). Idempotent.
   *
   * Cost when triggered: Metals' next startup re-imports the
   * build (1-3 minutes for a fresh sbt project) and re-indexes.
   * That's the price of recovering from a crashed peer; the
   * alternative is sitting deadlocked.
   */
  def reconcileStaleImportStatus(workspace: Path, hasLivePeer: Boolean): Unit = {
    if (hasLivePeer) return
    val mvDb = workspace.toAbsolutePath.normalize.resolve(".metals").resolve("metals.mv.db")
    if (Files.exists(mvDb)) {
      try {
        Files.delete(mvDb)
        scribe.warn(
          s"MetalsHealthCheck: removed stale $mvDb (no Sigil-managed Metals process alive — " +
            "previous run probably crashed mid-import). New Metals instance will re-import the build."
        )
      } catch {
        case t: Throwable =>
          scribe.warn(
            s"MetalsHealthCheck: failed to remove stale $mvDb: ${t.getMessage}. " +
              "Metals startup may deadlock if its `Started` import status is stale."
          )
      }
    }
  }
}
