package sigil.storage

import rapid.Task

import java.nio.file.{Files, Path}

/**
 * Filesystem-backed [[StorageProvider]]. Stores bytes under
 * `baseDir/<path>`. Default for dev / single-host deployments.
 */
final class LocalFileStorageProvider(baseDir: Path) extends StorageProvider {
  Files.createDirectories(baseDir)

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
}
