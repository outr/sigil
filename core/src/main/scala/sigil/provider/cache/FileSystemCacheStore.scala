package sigil.provider.cache

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * Filesystem-backed [[CacheStore]]. Each cached response lives at
 * `cacheRoot/<keyHash>.jsonl` as plain UTF-8 — diff-friendly under
 * `git`, no special tooling needed to inspect.
 *
 * `read` returns `None` when the file is absent; any other I/O failure
 * propagates. `write` creates intermediate directories as needed and
 * replaces any prior file atomically (write-then-replace via the
 * default `Files.writeString` semantics).
 */
final class FileSystemCacheStore(cacheRoot: Path) extends CacheStore {

  override def read(keyHash: String): Option[CachedResponse] = {
    val path = cacheRoot.resolve(s"$keyHash.jsonl")
    if (!Files.exists(path)) None
    else Some(CachedResponse.fromJsonl(Files.readString(path, StandardCharsets.UTF_8)))
  }

  override def write(keyHash: String, response: CachedResponse): Unit = {
    val path = cacheRoot.resolve(s"$keyHash.jsonl")
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(
      path,
      CachedResponse.toJsonl(response),
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )
    ()
  }
}
