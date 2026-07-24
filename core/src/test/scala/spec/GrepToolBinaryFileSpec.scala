package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.tool.fs.LocalFileSystemContext

import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * Coverage for `LocalFileSystemContext.searchFiles` robustness:
 *   1. Skip files containing NUL bytes (binary cache artifacts).
 *   2. Skip conventional cache directories (.bloop, .git, target,
 *      etc.) wholesale.
 *   3. Decode non-UTF-8 text files leniently (REPLACE-on-error)
 *      instead of crashing.
 *   4. Per-file failures (permission denied, truncated reads,
 *      etc.) become logged warnings, not unrecovered exceptions
 *      that escape the agent loop.
 */
class GrepToolBinaryFileSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private val fs = LocalFileSystemContext(basePath = None)

  private def newRoot(): Path =
    Files.createTempDirectory(s"grep-binary-${rapid.Unique()}-").toAbsolutePath.normalize

  private def writeBytes(p: Path, bytes: Array[Byte]): Unit = {
    Files.createDirectories(p.getParent)
    Files.write(
      p,
      bytes,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING)
    ()
  }

  private def cleanup(root: Path): Unit =
    if (Files.exists(root)) {
      import scala.jdk.CollectionConverters.*
      val s = Files.walk(root)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    }

  "searchFiles" should {

    "skip files with NUL bytes (binary cache artifacts) without crashing" in {
      val root = newRoot()
      writeBytes(root.resolve("good.scala"), "object Foo extends App".getBytes("UTF-8"))
      writeBytes(
        root.resolve("bad.bin"),
        Array[Byte](0x00, 0x01, 0x02, 0xff.toByte, 'F'.toByte, 'o'.toByte, 'o'.toByte))

      fs.searchFiles(root.toString, "Foo", glob = None, maxMatches = 100, contextLines = 0)
        .map { matches =>
          try {
            val paths = matches.map(_.filePath)
            paths should contain("good.scala")
            paths should not contain "bad.bin"
          } finally cleanup(root)
        }
    }

    "skip conventional cache directories (.bloop, target, .git, node_modules, .metals)" in {
      val root = newRoot()
      val excludedDirs = List(".bloop", "target", ".git", "node_modules", ".metals")
      excludedDirs.foreach { d =>
        writeBytes(
          root.resolve(s"$d/cache.bin"),
          Array[Byte](0x00, 0x01, 0x02, 'M'.toByte, 'a'.toByte, 'i'.toByte, 'n'.toByte))
      }
      writeBytes(root.resolve("Main.scala"), "object Main".getBytes("UTF-8"))

      fs.searchFiles(root.toString, "Main", glob = None, maxMatches = 100, contextLines = 0)
        .map { matches =>
          try {
            val paths = matches.map(_.filePath)
            paths should contain("Main.scala")
            // None of the excluded-dir files should appear, even if
            // their contents (binary or not) match the regex.
            paths.foreach { p =>
              excludedDirs.foreach { d =>
                p should not startWith s"$d/"
              }
            }
            succeed
          } finally cleanup(root)
        }
    }

    "tolerate non-UTF-8 text files (windows-1252 source) instead of crashing" in {
      val root = newRoot()
      // 'café' encoded as windows-1252: 'c','a','f', 0xE9. The 0xE9
      // byte is invalid UTF-8 standalone — strict UTF-8 decode would
      // throw MalformedInputException; the lenient decoder substitutes
      // U+FFFD and the prefix "caf" still matches.
      val cp1252 = Array[Byte]('/'.toByte, '/'.toByte, ' '.toByte, 'c'.toByte, 'a'.toByte, 'f'.toByte, 0xe9.toByte)
      writeBytes(root.resolve("Legacy.java"), cp1252)

      fs.searchFiles(root.toString, "caf", glob = None, maxMatches = 100, contextLines = 0)
        .map { matches =>
          try matches.map(_.filePath) should contain("Legacy.java")
          finally cleanup(root)
        }
    }

    "complete even when individual files fail to read (one bad file != loop crash)" in {
      val root = newRoot()
      writeBytes(root.resolve("readable.scala"), "object Readable".getBytes("UTF-8"))
      // Create a regular file then drop read permission so the per-file
      // open throws inside searchFiles. The wrapping try/catch must
      // log+skip and the search must still return matches from the
      // readable file.
      val unreadable = root.resolve("unreadable.scala")
      writeBytes(unreadable, "object Unreadable".getBytes("UTF-8"))
      try
        Files.setPosixFilePermissions(unreadable, java.util.Set.of())
      catch {
        case _: UnsupportedOperationException => () // Windows / non-POSIX FS — test still meaningful otherwise
      }

      fs.searchFiles(root.toString, "object", glob = None, maxMatches = 100, contextLines = 0)
        .map { matches =>
          try {
            val paths = matches.map(_.filePath)
            paths should contain("readable.scala")
            // unreadable.scala may or may not appear depending on the
            // running user (root reads regardless of bits); the
            // critical assertion is just that searchFiles returned
            // without throwing.
          } finally {
            try Files.setPosixFilePermissions(
                unreadable,
                java.util.Set.of(
                  java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                  java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            catch { case _: Throwable => () }
            cleanup(root)
          }
        }
    }
  }
}
