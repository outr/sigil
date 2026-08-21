package sigil.cache

import lightdb.id.Id
import rapid.Task
import sigil.db.Model

import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory [[ModelSource]] whose slice is swapped atomically.
 *
 * The default shape for everything that side-loads models: a provider
 * seeding the catalog its backend reports at construction, a vendor
 * helper re-reading a deployment list, the aggregate catalog landing a
 * fresh fetch. [[set]] is the whole-slice swap (models the backing
 * catalog dropped disappear with it); [[merge]] adds to the slice
 * without disturbing the rest of it.
 */
class MutableModelSource(val name: String) extends ModelSource {

  private val ref: AtomicReference[Map[Id[Model], Model]] =
    new AtomicReference(Map.empty)

  override def models: List[Model] = ref.get.values.toList

  /** Replace this slice with `models`. Entries this source previously
    * held and `models` doesn't name are dropped; no other source's
    * entries are touched. */
  def set(models: List[Model]): Task[Unit] = Task {
    ref.set(models.iterator.map(m => m._id -> m).toMap)
    sliceChanged()
  }

  /** Add `models` to this slice, overwriting same-id entries it
    * already held and leaving the rest in place. */
  def merge(models: List[Model]): Task[Unit] = Task {
    val _ = ref.updateAndGet(current => models.foldLeft(current)((acc, m) => acc + (m._id -> m)))
    sliceChanged()
  }
}
