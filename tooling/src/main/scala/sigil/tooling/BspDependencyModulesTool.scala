package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspDependencyModule, BspDependencyModulesResult, BspTargetDependencyModules}

import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

case class BspDependencyModulesInput(projectRoot: String,
                                     targets: List[String] = Nil) extends ToolInput derives RW

/**
 * List each target's library dependencies as Maven coordinates
 * (or whatever the build server's module resolver returns —
 * `org:artifact:version` for Scala/sbt).
 *
 * Higher-level than `bsp_dependency_sources`: that returns jar
 * paths; this returns the coordinates the build references. Useful
 * for "what version of X does this project pull in?"
 */
final class BspDependencyModulesTool(val manager: BspManager) extends TypedOutputTool[BspDependencyModulesInput, BspDependencyModulesResult](
  name = ToolName("bsp_dependency_modules"),
  description =
    """List each target's library dependencies as module coordinates (groupId:artifactId, version).
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is the list of target URIs; empty queries every workspace target.""".stripMargin,
  keywords = Set("bsp", "dependencies", "deps", "modules", "library deps"),
  examples = List(
    ToolExample(
      "list dependency modules",
      BspDependencyModulesInput(projectRoot = "/abs/path/myproject")
    )
  )
) with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  override protected def executeTyped(input: BspDependencyModulesInput,
                                      context: TurnContext): Task[BspDependencyModulesResult] = {
    val cacheKey = BspDependencyModulesTool.cacheKeyFor(input.projectRoot, input.targets)
    cacheKey.flatMap {
      case Some(key) =>
        Option(BspDependencyModulesTool.cache.get(key)) match {
          case Some(cached) =>
            context.reportProgress("Using cached dependency modules (build.sbt unchanged)")
              .handleError(_ => Task.unit)
              .map(_ => cached)
          case None =>
            compute(input, context).map { result =>
              if (result.items.nonEmpty) BspDependencyModulesTool.cache.put(key, result)
              result
            }
        }
      case None =>
        // No build.sbt fingerprint available — skip caching, run fresh.
        compute(input, context)
    }
  }

  private def compute(input: BspDependencyModulesInput,
                      context: TurnContext): Task[BspDependencyModulesResult] =
    withSessionTyped[BspDependencyModulesResult](
      input.projectRoot, context,
      onError = _ => BspDependencyModulesResult(input.projectRoot, Nil)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) Task.pure(BspDependencyModulesResult(input.projectRoot, Nil))
        else session.dependencyModules(targets).map { items =>
          BspDependencyModulesResult(
            projectRoot = input.projectRoot,
            items = items.map { item =>
              BspTargetDependencyModules(
                target  = item.getTarget.getUri,
                modules = Option(item.getModules).map(_.asScala.toList.map { m =>
                  BspDependencyModule(name = m.getName, version = m.getVersion)
                }).getOrElse(Nil)
              )
            }
          )
        }
      }
    }
}

object BspDependencyModulesTool {

  /** Cache key — projectRoot + sorted target URIs + build.sbt
    * mtime + content hash. `build.sbt` is the canonical dependency
    * declaration for sbt projects; mtime + hash detects any change
    * (including `// no-op` edits that bump only the mtime).
    *
    * For non-sbt projects with no recognizable build manifest the
    * key is `None` and the cache is skipped entirely. */
  final case class Key(projectRoot: String,
                       targets: List[String],
                       buildSbtMtime: Long,
                       buildSbtHash: String)

  /** Process-wide cache. Entries live for the JVM's lifetime — apps
    * with longer-than-process sessions can flush by replacing the
    * map (testing) or by calling [[invalidate]]. */
  val cache: ConcurrentHashMap[Key, BspDependencyModulesResult] = new ConcurrentHashMap()

  /** Hard reset — used by specs and by ops "clear caches" flows. */
  def invalidate(): Unit = cache.clear()

  /** Build a cache key for `projectRoot` + `targets`. Returns `None`
    * when the project has no `build.sbt` (or it isn't readable) so
    * the caller falls back to running the BSP call uncached. */
  def cacheKeyFor(projectRoot: String, targets: List[String]): Task[Option[Key]] = Task {
    val buildFile = Paths.get(projectRoot, "build.sbt")
    if (!Files.isRegularFile(buildFile)) None
    else {
      val mtime = Files.getLastModifiedTime(buildFile).toMillis
      val bytes = Files.readAllBytes(buildFile)
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      val hash = digest.iterator.map(b => f"${b & 0xff}%02x").mkString
      Some(Key(
        projectRoot   = projectRoot,
        targets       = targets.sorted,
        buildSbtMtime = mtime,
        buildSbtHash  = hash
      ))
    }
  }.handleError(_ => Task.pure(None))
}
