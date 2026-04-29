package sigil.tooling

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.{GlobalSpace, SpaceId}

/**
 * Persisted configuration for a build-server binding (sbt / Bloop /
 * Mill). Stored in [[ToolingCollections.bspBuilds]] and loaded by
 * [[BspManager]] on first compile / test request.
 *
 * BSP discovery convention: each project's `.bsp/<server>.json` file
 * tells consumers how to launch the server. Apps either persist
 * pre-discovered configs here or let [[BspManager]] auto-discover
 * from a project root when this collection is empty for that root.
 *
 * @param projectRoot   absolute path to the project root (the
 *                      directory containing `.bsp/`). Doubles as the
 *                      session cache key.
 * @param command       executable to spawn (e.g. `sbt -bsp`,
 *                      `bloop bsp`). Discovered from the
 *                      `.bsp/<server>.json` `argv` field by
 *                      auto-discovery, or set explicitly by apps.
 * @param args          additional arguments passed after `command`.
 * @param env           extra environment variables.
 * @param idleTimeoutMs inactivity window before the build server is
 *                      shut down. Default 30 min — sbt warm-up
 *                      especially is expensive.
 * @param space         scope for tools that resolve through this
 *                      config. Defaults to [[GlobalSpace]].
 */
case class BspBuildConfig(projectRoot: String,
                          command: String,
                          args: List[String] = Nil,
                          env: Map[String, String] = Map.empty,
                          idleTimeoutMs: Long = 30L * 60L * 1000L,
                          space: SpaceId = GlobalSpace,
                          created: Timestamp = Timestamp(),
                          modified: Timestamp = Timestamp(),
                          _id: Id[BspBuildConfig] = BspBuildConfig.id())
  extends RecordDocument[BspBuildConfig]

object BspBuildConfig extends RecordDocumentModel[BspBuildConfig] with JsonConversion[BspBuildConfig] {
  implicit override def rw: RW[BspBuildConfig] = RW.gen

  override def id(value: String = rapid.Unique()): Id[BspBuildConfig] = Id(value)

  /** Stable id derived from project root — one record per root. */
  def idFor(projectRoot: String): Id[BspBuildConfig] = Id(projectRoot)
}
