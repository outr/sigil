package sigil.metals

import java.nio.file.{Files, Path}

/**
 * Self-healing helpers for Metals' on-disk state. Sigil bug #99 —
 * a Metals process killed mid-import leaves the embedded H2 db
 * (`<workspace>/.metals/metals.mv.db`) at status `Started`. Every
 * subsequent Metals startup reads that, sees Started, logs
 * `skipping build import with status 'Started'`, and sits idle.
 * No `.bloop/` ever appears; every LSP/BSP tool that depends on
 * indexing hangs.
 *
 * The recovery today is manual: kill all Metals java processes
 * targeting the workspace, wipe `metals.mv.db`, restart. Not a
 * workflow we can ask users to discover.
 *
 * This helper runs at every Metals spawn entry point. When no
 * Sigil-managed peer is alive for the workspace, the H2 db is
 * presumed stale (whoever wrote `Started` is dead) and gets
 * removed so the new Metals starts with a fresh `Pending`
 * status and runs a clean import.
 */
object MetalsHealthCheck {

  /** Reconcile a possibly-stale Metals H2 db before spawning a
    * fresh Metals process. When `hasLivePeer = false` the file is
    * removed; when true it's left alone (the live peer owns its
    * own state). No-op when the file doesn't exist (first-ever
    * spawn). Idempotent.
    *
    * Cost when triggered: Metals' next startup re-imports the
    * build (1-3 minutes for a fresh sbt project) and re-indexes.
    * That's the price of recovering from a crashed peer; the
    * alternative is sitting deadlocked. */
  def reconcileStaleImportStatus(workspace: Path, hasLivePeer: Boolean): Unit = {
    if (hasLivePeer) return
    val mvDb = workspace.toAbsolutePath.normalize.resolve(".metals").resolve("metals.mv.db")
    if (Files.exists(mvDb)) {
      try {
        Files.delete(mvDb)
        scribe.warn(
          s"MetalsHealthCheck: removed stale ${mvDb} (no Sigil-managed Metals process alive — " +
            "previous run probably crashed mid-import). New Metals instance will re-import the build."
        )
      } catch {
        case t: Throwable =>
          scribe.warn(
            s"MetalsHealthCheck: failed to remove stale ${mvDb}: ${t.getMessage}. " +
              "Metals startup may deadlock if its `Started` import status is stale."
          )
      }
    }
  }
}
