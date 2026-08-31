package sigil.cache

import sigil.db.Model

import java.util.concurrent.atomic.AtomicReference

/**
 * An independently-maintained slice of the model catalog.
 *
 * A [[ModelRegistry]] composes sources — it never owns records itself.
 * Each source keeps its own snapshot and swaps it whole when its
 * backing catalog changes (the aggregate catalog refreshing on its
 * interval, a llama.cpp server coming back with a different model
 * loaded), then calls [[sliceChanged]] so the registry rebuilds its
 * composite index. A source can only ever add, update, or remove its
 * OWN entries: one source's refresh cannot evict another's models.
 *
 * Implementations are typically [[MutableModelSource]]; apps that back
 * a slice with their own store implement the trait directly, returning
 * a live snapshot from [[models]] and signalling [[sliceChanged]] on
 * every change.
 */
trait ModelSource {

  /**
   * Stable identity for this slice. Registering a source whose name
   * already exists swaps the implementation in place, keeping the
   * original registration position (and therefore its precedence).
   */
  def name: String

  /**
   * Current snapshot of the models this source serves. Read on every
   * composite-index rebuild, never on the read hot path.
   */
  def models: List[Model]

  private val listeners: AtomicReference[List[ModelSource => Unit]] =
    new AtomicReference(Nil)

  /**
   * Announce that this source's slice changed. Implementations call
   * this after the swap, so every registry composing the source
   * rebuilds against the new snapshot.
   */
  final protected def sliceChanged(): Unit = listeners.get.foreach(_(this))

  final private[cache] def subscribe(listener: ModelSource => Unit): Unit = {
    val _ = listeners.updateAndGet(listener :: _)
    ()
  }
}
