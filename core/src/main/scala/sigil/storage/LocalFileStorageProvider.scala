package sigil.storage

import lightdb.time.Timestamp
import rapid.Task

import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Filesystem-backed [[StorageProvider]]. Stores bytes under
 * `baseDir/<path>`. Default for dev / single-host deployments.
 *
 * Safe-edit support: [[read]] returns SHA-256 over the bytes plus
 * the file's wall-clock mtime; [[writeIfMatch]] guards the actual
 * write with a per-path [[ReentrantLock]] so two concurrent CAS
 * callers can't both pass the version check and both write. The
 * locks are process-local — multi-process / multi-host scenarios
 * need OS-level locking, out of scope for this single-JVM impl.
 */
class LocalFileStorageProvider(baseDir: Path) extends StorageProvider {
  Files.createDirectories(baseDir)

  /** Per-canonical-path locks. Map entries persist for the lifetime
    * of the process — bounded by the set of distinct paths ever
    * touched, which for typical workloads is small. Apps that
    * generate millions of distinct paths should add their own
    * cleanup policy. */
  private val locks: ConcurrentHashMap[String, ReentrantLock] = new ConcurrentHashMap()

  /** Diagnostic — number of distinct paths currently holding a lock
    * record. Useful for ops dashboards (lock-map cardinality is the
    * memory cost of the safe-edit feature) and for tests asserting
    * per-path lock independence. */
  def lockCount: Int = locks.size()

  override def upload(path: String, data: Array[Byte], contentType: String): Task[String] = Task {
    val target = baseDir.resolve(path)
    Option(target.getParent).foreach(Files.createDirectories(_))
    Files.write(target, data)
    path
  }

  override def download(path: String): Task[Option[Array[Byte]]] = Task {
    val target = baseDir.resolve(path)
    if (Files.exists(target)) Some(Files.readAllBytes(target)) else None
  }

  override def delete(path: String): Task[Unit] = Task {
    Files.deleteIfExists(baseDir.resolve(path))
    ()
  }

  override def exists(path: String): Task[Boolean] = Task {
    Files.exists(baseDir.resolve(path))
  }

  override def read(path: String): Task[Option[StorageContents]] = Task {
    val target = baseDir.resolve(path)
    if (!Files.exists(target)) None
    else {
      val bytes = Files.readAllBytes(target)
      Some(StorageContents(bytes, versionOf(target, bytes)))
    }
  }

  override def writeIfMatch(path: String,
                            data: Array[Byte],
                            contentType: String,
                            expected: FileVersion): Task[WriteResult] = Task {
    val target = baseDir.resolve(path)
    val lock = locks.computeIfAbsent(target.toAbsolutePath.normalize().toString, _ => new ReentrantLock())
    lock.lock()
    try {
      if (!Files.exists(target)) WriteResult.NotFound
      else {
        val currentBytes = Files.readAllBytes(target)
        val currentVersion = versionOf(target, currentBytes)
        if (currentVersion.hash != expected.hash) {
          WriteResult.Stale(StorageContents(currentBytes, currentVersion))
        } else {
          Option(target.getParent).foreach(Files.createDirectories(_))
          Files.write(target, data)
          WriteResult.Written(versionOf(target, data))
        }
      }
    } finally lock.unlock()
  }

  protected def versionOf(target: Path, bytes: Array[Byte]): FileVersion = {
    val mtime = try {
      val attrs = Files.readAttributes(target, classOf[BasicFileAttributes])
      Timestamp(attrs.lastModifiedTime().toMillis)
    } catch {
      case _: Throwable => Timestamp()
    }
    FileVersion(FileVersion.hashOf(bytes), mtime)
  }
}
