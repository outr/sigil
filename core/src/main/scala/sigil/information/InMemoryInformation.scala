package sigil.information

import lightdb.id.Id
import rapid.Task

import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory [[Information]] store apps can wire into
 * [[sigil.Sigil.getInformation]] for tests, demos, or single-JVM
 * deployments that don't need durable persistence.
 *
 * Usage:
 * {{{
 *   val info = new InMemoryInformation
 *   // Override on your Sigil:
 *   override def getInformation(id) = info.get(id)
 *
 *   // Populate as your app ingests content:
 *   info.put(myInformation)
 * }}}
 *
 * Thread-safe. No TTL / eviction.
 */
final class InMemoryInformation {
  private val store = new ConcurrentHashMap[Id[Information], Information]()

  def put(info: Information): Unit = { store.put(info.id, info); () }

  def remove(id: Id[Information]): Unit = { store.remove(id); () }

  def clear(): Unit = store.clear()

  /**
   * Lookup — Task-shaped so callers can plug directly into `Sigil.getInformation`.
   */
  def get(id: Id[Information]): Task[Option[Information]] =
    Task.pure(Option(store.get(id)))

  /**
   * Current count — useful in tests.
   */
  def size: Int = store.size
}
