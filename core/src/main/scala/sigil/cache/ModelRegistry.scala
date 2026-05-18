package sigil.cache

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.db.Model

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory registry of catalog [[Model]] records. Reads are
 * synchronous (single AtomicReference dereference) so hot paths like
 * `Provider.models` and `isImageOnlyModel` don't pay a DB round-trip
 * per call.
 *
 * Writes happen via [[replace]] each time the upstream catalog
 * (OpenRouter) is refreshed. Optional disk fallback: when
 * `cachePath` is set, [[replace]] persists the records as JSON,
 * and [[loadFromDisk]] (called at Sigil init) restores from that
 * file if the network is unreachable on boot.
 *
 * Disk writes go to `<cachePath>.tmp` first and atomic-rename to
 * the final path so a crash mid-write can't corrupt the cache.
 */
final class ModelRegistry(cachePath: Option[Path] = None) {

  private val ref: AtomicReference[Map[Id[Model], Model]] =
    new AtomicReference(Map.empty)

  /**
   * Every Model currently in the registry.
   */
  def all: List[Model] = ref.get.values.toList

  /**
   * Resolve by full id (`<provider>/<model>`).
   */
  def find(modelId: Id[Model]): Option[Model] = ref.get.get(modelId)

  /**
   * Resolve by id with a bare-form fallback. Tries exact match first;
   * on miss, walks the registry for an entry whose id ends with
   * `"/$raw"` (the prefixed-with-provider form OpenRouter populates
   * when an app stamps the bare model name on a Message — common
   * because apps configure candidates as `Model.id("gpt-5.5")` while
   * the catalog ships `Model.id("openai/gpt-5.5")`).
   *
   * Bug #91 — without this fallback the cost projection silently
   * misses on every Message stamped with a bare id.
   *
   * Hot-path safe: exact match takes the AtomicReference dereference
   * + map lookup; only a miss pays the linear walk.
   */
  def findTolerant(modelId: Id[Model]): Option[Model] = {
    val direct = ref.get.get(modelId)
    if (direct.isDefined) direct
    else {
      val raw = modelId.value
      val suffix = s"/$raw"
      ref.get.values.find(m => m._id.value == raw || m._id.value.endsWith(suffix))
    }
  }

  /**
   * The registry's canonical id for `modelId`, when known. Returns
   * the input unchanged when nothing in the registry matches. Useful
   * at write boundaries (Provider stamping the modelId onto outgoing
   * Messages) so future events carry the prefixed form and don't
   * need the [[findTolerant]] fallback at projection time.
   */
  def canonicalIdFor(modelId: Id[Model]): Id[Model] =
    findTolerant(modelId).map(_._id).getOrElse(modelId)

  /**
   * Filtered listing. Empty filters return everything; supplying
   * `provider`/`model` narrows by exact match (case-insensitive,
   * matching `Model.id`'s lowercase normalization).
   */
  def find(provider: Option[String] = None, model: Option[String] = None): List[Model] = {
    val p = provider.map(_.toLowerCase)
    val m = model.map(_.toLowerCase)
    if (p.isEmpty && m.isEmpty) all
    else ref.get.values.iterator.filter { mod =>
      p.forall(_ == mod.provider) && m.forall(_ == mod.model)
    }.toList
  }

  /**
   * Convenience overload — provider+model as strings, returning
   * `Option[Model]`.
   */
  def find(provider: String, model: String): Option[Model] =
    find(Model.id(provider, model))

  /**
   * Atomically replace the registry with `models` and (when a cache
   * path is configured) persist them to disk. Used by
   * `OpenRouter.refreshModels` after a successful upstream fetch
   * — the OpenRouter catalog is the single aggregate source for
   * that path, so a wholesale replace is correct semantics.
   */
  def replace(models: List[Model]): Task[Unit] = Task {
    ref.set(models.iterator.map(m => m._id -> m).toMap)
  }.flatMap(_ => writeToDisk(ref.get.values.toList))

  /**
   * Additively merge `models` into the registry. Existing entries
   * with the same id are overwritten; unrelated entries are
   * preserved. Used by per-provider seeding where each provider
   * (LlamaCpp, Anthropic, OpenAI, …) brings its own catalog and
   * coexists with others in the same Sigil instance.
   *
   * Persists the post-merge state to disk when [[cachePath]] is
   * set — same atomic-rename guarantee as [[replace]].
   */
  def merge(models: List[Model]): Task[Unit] = Task {
    val updated = ref.updateAndGet { current =>
      models.foldLeft(current)((acc, m) => acc + (m._id -> m))
    }
    updated.values.toList
  }.flatMap(writeToDisk)

  /**
   * Restore the registry from disk if a cache file exists. Used at
   * Sigil init so the first run after a network outage still has
   * the catalog from the prior successful refresh.
   */
  def loadFromDisk: Task[Unit] = cachePath match {
    case None => Task.unit
    case Some(path) =>
      Task(Files.exists(path)).flatMap {
        case false => Task.unit
        case true => Task {
            val raw = Files.readString(path)
            val parsed = JsonParser(raw)
            val records = parsed.asVector.map(_.as[Model]).toList
            ref.set(records.iterator.map(m => m._id -> m).toMap)
            ()
          }
      }
  }

  private def writeToDisk(models: List[Model]): Task[Unit] = cachePath match {
    case None => Task.unit
    case Some(path) => Task {
        Option(path.getParent).foreach(Files.createDirectories(_))
        val tmp = path.resolveSibling(path.getFileName.toString + ".tmp")
        val json = JsonFormatter.Compact(models.json)
        Files.writeString(tmp, json)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        ()
      }
  }
}
