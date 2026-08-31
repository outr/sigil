package sigil.cache

import lightdb.id.Id
import rapid.Task
import sigil.db.Model

import java.util.concurrent.atomic.AtomicReference

/**
 * Federated in-memory registry of [[Model]] records.
 *
 * The registry composes [[ModelSource]]s — it never owns records
 * itself. Each source maintains its own slice (the aggregate catalog,
 * a llama.cpp server's loaded models, an app's hand-built records) and
 * announces changes; the registry folds every slice into one composite
 * index at that moment. Reads stay a single `AtomicReference` deref
 * plus map lookup, so `Provider.models` and `isImageOnlyModel`-style
 * hot paths pay nothing for the federation — combination happens on
 * write, never on read.
 *
 * The structural consequence is the point: a source can only replace
 * its OWN slice, so refreshing one catalog can never evict another
 * source's models.
 *
 * '''Precedence.''' When two sources carry the same model id, the
 * most recently registered source wins. Built-ins register first, in
 * order: app side-loads ([[appSource]], fed by [[merge]]), then the
 * aggregate catalog ([[catalogSource]], fed by
 * `OpenRouter.refreshModels` and the persisted `db.models` snapshot).
 * So a bare [[merge]] fills what the catalog doesn't carry — a local
 * model, a private deployment — and the catalog stays authoritative
 * for the ids it does carry. Everything registered afterwards outranks
 * both: a provider reading its own running backend, or an app's own
 * source of curated overrides. Apps control the outcome by
 * registration order.
 *
 * Persistence lives with the source that owns the slice: the catalog
 * source's snapshot is written to `db.models` by
 * `OpenRouter.refreshModels` and restored at boot by
 * `Sigil.instance`. The registry itself never touches disk or the
 * network.
 */
final class ModelRegistry {

  private val registered: AtomicReference[Vector[ModelSource]] = new AtomicReference(Vector.empty)
  private val index: AtomicReference[Map[Id[Model], Model]] = new AtomicReference(Map.empty)

  /**
   * The app side-load slice — records registered through [[merge]].
   * Registered first, so it sits below the aggregate catalog: a merge
   * fills gaps the catalog doesn't cover rather than overriding it.
   * An app that wants to outrank the catalog registers its own source
   * instead.
   */
  val appSource: MutableModelSource = source(ModelRegistry.AppSourceName)

  /**
   * The aggregate-catalog slice — OpenRouter's fetch and the
   * persisted snapshot that mirrors it.
   */
  val catalogSource: MutableModelSource = source(ModelRegistry.CatalogSourceName)

  /**
   * Every registered source, in registration (ascending precedence) order.
   */
  def sources: List[ModelSource] = registered.get.toList

  /**
   * Register `source` and compose its slice into the index. A source
   * whose name is already registered is swapped in place, keeping the
   * original registration position and precedence, so re-registering
   * is idempotent. Later registrations outrank earlier ones for a
   * colliding model id.
   */
  def register(source: ModelSource): Task[Unit] = Task {
    registerNow(source)
    ()
  }

  /**
   * The registered [[MutableModelSource]] named `name`, creating and
   * registering it on first use. The ergonomic path for a provider or
   * vendor helper that wants its own slice:
   * `sigil.cache.source("llamacpp@" + url).set(models)`.
   */
  def source(name: String): MutableModelSource = synchronized {
    registered.get.find(_.name == name) match {
      case Some(mutable: MutableModelSource) => mutable
      case Some(other) =>
        throw new IllegalArgumentException(
          s"Model source '$name' is already registered as ${other.getClass.getSimpleName}, which manages its own slice. " +
            s"Update it through its own API, or register under a different name."
        )
      case None =>
        val created = new MutableModelSource(name)
        registerNow(created)
        created
    }
  }

  /**
   * Every Model currently visible, precedence applied.
   */
  def all: List[Model] = index.get.values.toList

  /**
   * Per-source slice sizes, in precedence order — the observable for
   * "what did this refresh actually change".
   */
  def sliceSummary: String = registered.get.map(s => s"${s.name}=${s.models.size}").mkString(", ")

  /**
   * Resolve by full id (`<provider>/<model>`).
   */
  def find(modelId: Id[Model]): Option[Model] = index.get.get(modelId)

  /**
   * Resolve by id with a bare-form fallback. Tries exact match first;
   * on miss, walks the registry for an entry whose id ends with
   * `"/$raw"` (the prefixed-with-provider form OpenRouter populates
   * when an app stamps the bare model name on a Message — common
   * because apps configure candidates as `Model.id("gpt-5.5")` while
   * the catalog ships `Model.id("openai/gpt-5.5")`).
   *
   * Hot-path safe: exact match takes the AtomicReference dereference
   * + map lookup; only a miss pays the linear walk.
   */
  def findTolerant(modelId: Id[Model]): Option[Model] = {
    val direct = index.get.get(modelId)
    if (direct.isDefined) direct
    else {
      // Sigil #374 — normalize across the three axes that cause a registered
      // model to miss an exact lookup: case, provider prefix, and `.`/`-`
      // separators. So `claude-3-5-sonnet` matches `anthropic/claude-3.5-sonnet`.
      val target = ModelRegistry.normalizeForMatch(modelId.value)
      index.get.values.find(m => ModelRegistry.normalizeForMatch(m._id.value) == target)
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
    else index.get.values.iterator.filter { mod =>
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
   * Swap the aggregate-catalog slice ([[catalogSource]]) for
   * `models` — the OpenRouter refresh path and the persisted-snapshot
   * boot seed.
   *
   * Scoped, not wholesale: catalog models the upstream dropped
   * disappear, while every other source's slice (a provider's
   * backend catalog, app side-loads) is untouched. Sources other than
   * the catalog swap their own slice through their own API.
   */
  def replace(models: List[Model]): Task[Unit] = catalogSource.set(models)

  /**
   * Merge `models` into the app side-load slice ([[appSource]]).
   * Existing entries in that slice with the same id are overwritten;
   * unrelated entries — in this slice or any other — are preserved,
   * so side-loaded records outlive every aggregate-catalog refresh.
   * The slice sits below the catalog, so an id the catalog also
   * carries resolves to the catalog's record; register a dedicated
   * source to override one.
   */
  def merge(models: List[Model]): Task[Unit] = appSource.merge(models)

  private def registerNow(source: ModelSource): Unit = synchronized {
    val current = registered.get
    val updated = current.indexWhere(_.name == source.name) match {
      case -1 => current :+ source
      case i => current.updated(i, source)
    }
    registered.set(updated)
    source.subscribe(_ => rebuild())
    rebuild()
  }

  private def rebuild(): Unit = synchronized {
    index.set(registered.get.foldLeft(Map.empty[Id[Model], Model]) { (acc, source) =>
      acc ++ source.models.iterator.map(m => m._id -> m)
    })
  }
}

object ModelRegistry {

  /**
   * Name of the built-in aggregate-catalog source.
   */
  val CatalogSourceName: String = "catalog"

  /**
   * Name of the built-in app side-load source.
   */
  val AppSourceName: String = "app"

  /**
   * Sigil #374 — normalize a model id for tolerant matching: lowercase, drop
   * any provider prefix (the segment before the first `/`), and collapse `.`
   * and `-` separators to a single form. So `claude-3-5-sonnet`,
   * `anthropic/claude-3.5-sonnet`, and `Anthropic/Claude-3.5-Sonnet` all
   * normalize equal. Best-effort: a bare id that collides across providers
   * resolves to the first registry match — the exact path handles precise
   * lookups; this only rescues format variants of a registered model.
   */
  private[sigil] def normalizeForMatch(raw: String): String = {
    val lower = raw.toLowerCase
    val afterPrefix = lower.indexOf('/') match {
      case -1 => lower
      case i => lower.substring(i + 1)
    }
    afterPrefix.replace('.', '-')
  }
}
