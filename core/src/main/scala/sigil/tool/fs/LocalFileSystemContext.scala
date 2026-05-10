package sigil.tool.fs

import lightdb.time.Timestamp
import rapid.Task
import sigil.storage.{FileVersion, StorageContents, WriteResult}

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileSystems, Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*

/**
 * Default [[FileSystemContext]] — runs against the local
 * filesystem and a `bash -c` shell. Optional `basePath` confines
 * every operation to a subtree (paths that would escape via `..`
 * resolution are rejected with a `SecurityException`).
 *
 * Output truncation: shell stdout/stderr is capped at
 * [[OutputTruncationBytes]] per stream; if a process produces more,
 * its tail is silently dropped (the captured prefix still appears
 * in the result).
 */
class LocalFileSystemContext(basePath: Option[Path] = None) extends FileSystemContext {
  import LocalFileSystemContext.OutputTruncationBytes

  override def executeCommand(command: String,
                              workingDir: Option[String],
                              timeoutMs: Long): Task[CommandResult] = Task {
    val dir = workingDir.map(d => resolvePath(d).toFile).orElse(basePath.map(_.toFile)).orNull
    val pb  = new ProcessBuilder("bash", "-c", command)
    if (dir != null) pb.directory(dir)
    pb.redirectErrorStream(false)

    val process       = pb.start()
    val stdoutBuilder = new StringBuilder
    val stderrBuilder = new StringBuilder

    val stdoutThread = new Thread(() => drainStream(process.getInputStream, stdoutBuilder))
    val stderrThread = new Thread(() => drainStream(process.getErrorStream, stderrBuilder))
    stdoutThread.setDaemon(true); stdoutThread.start()
    stderrThread.setDaemon(true); stderrThread.start()

    val completed = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    if (!completed) {
      process.destroyForcibly()
      stdoutThread.join(1000)
      stderrThread.join(1000)
      CommandResult(stdoutBuilder.toString.trim, "Process timed out and was killed", 137)
    } else {
      stdoutThread.join(5000)
      stderrThread.join(5000)
      CommandResult(stdoutBuilder.toString.trim, stderrBuilder.toString.trim, process.exitValue())
    }
  }

  override def readFile(filePath: String): Task[String] = Task {
    Files.readString(resolvePath(filePath), StandardCharsets.UTF_8)
  }

  override def readFileLines(filePath: String, offset: Int, limit: Int): Task[(List[String], Int)] = Task {
    val all = Files.readAllLines(resolvePath(filePath), StandardCharsets.UTF_8).asScala.toList
    (all.drop(offset).take(limit), all.size)
  }

  override def writeFile(filePath: String, content: String): Task[Long] = Task {
    val path = resolvePath(filePath)
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(path, content, StandardCharsets.UTF_8)
    content.getBytes(StandardCharsets.UTF_8).length.toLong
  }

  override def listFiles(basePath: String, pattern: String, maxResults: Int): Task[List[String]] = Task {
    val base    = resolvePath(basePath)
    val matcher = FileSystems.getDefault.getPathMatcher(s"glob:$pattern")
    val results = scala.collection.mutable.ListBuffer.empty[String]
    val stream  = Files.walk(base)
    try {
      stream.iterator().asScala
        .filter(p => matcher.matches(base.relativize(p)) || matcher.matches(p.getFileName))
        .take(maxResults)
        .foreach(p => results += base.relativize(p).toString)
    } finally stream.close()
    results.toList
  }

  override def searchFiles(basePath: String,
                           pattern: String,
                           glob: Option[String],
                           maxMatches: Int,
                           contextLines: Int): Task[List[GrepMatch]] = Task {
    val base        = resolvePath(basePath)
    val regex       = Pattern.compile(pattern)
    val globMatcher = glob.map(g => FileSystems.getDefault.getPathMatcher(s"glob:$g"))
    val results     = scala.collection.mutable.ListBuffer.empty[GrepMatch]
    val stream      = Files.walk(base)
    try {
      stream.iterator().asScala
        .filter(p => !isInExcludedDir(base, p))
        .filter(Files.isRegularFile(_))
        .filter(p => globMatcher.forall(m => m.matches(p.getFileName) || m.matches(base.relativize(p))))
        .takeWhile(_ => results.size < maxMatches)
        .foreach { path =>
          try {
            if (!isBinary(path)) {
              val lines = readLinesLenient(path)
              lines.zipWithIndex.foreach { case (line, idx) =>
                if (regex.matcher(line).find() && results.size < maxMatches) {
                  val before = lines.slice(Math.max(0, idx - contextLines), idx)
                  val after  = lines.slice(idx + 1, Math.min(lines.size, idx + 1 + contextLines))
                  results += GrepMatch(
                    filePath      = base.relativize(path).toString,
                    lineNumber    = idx + 1,
                    content       = line,
                    contextBefore = before,
                    contextAfter  = after
                  )
                }
              }
            }
          } catch {
            case t: Throwable =>
              scribe.warn(s"grep: skipping ${base.relativize(path)} — ${t.getClass.getSimpleName}: ${t.getMessage}")
          }
        }
    } finally stream.close()
    results.toList
  }

  override def deleteFile(filePath: String): Task[Boolean] = Task {
    Files.deleteIfExists(resolvePath(filePath))
  }

  override def readContents(filePath: String): Task[Option[StorageContents]] = Task {
    val target = resolvePath(filePath)
    if (!Files.exists(target)) None
    else {
      val bytes = Files.readAllBytes(target)
      Some(StorageContents(bytes, versionOf(target, bytes)))
    }
  }

  override def writeIfMatch(filePath: String, content: String, expected: FileVersion): Task[WriteResult] = Task {
    val target = resolvePath(filePath)
    val key = target.toString
    val lock = locks.computeIfAbsent(key, _ => new ReentrantLock())
    lock.lock()
    try {
      if (!Files.exists(target)) WriteResult.NotFound
      else {
        val currentBytes = Files.readAllBytes(target)
        val currentVersion = versionOf(target, currentBytes)
        if (currentVersion.hash != expected.hash) {
          WriteResult.Stale(StorageContents(currentBytes, currentVersion))
        } else {
          val data = content.getBytes(StandardCharsets.UTF_8)
          Option(target.getParent).foreach(Files.createDirectories(_))
          Files.write(target, data)
          WriteResult.Written(versionOf(target, data))
        }
      }
    } finally lock.unlock()
  }

  /** Per-canonical-path locks for safe-edit. Process-local — see
    * [[sigil.storage.LocalFileStorageProvider]] for the same pattern
    * applied to opaque storage paths. */
  private val locks: ConcurrentHashMap[String, ReentrantLock] = new ConcurrentHashMap()

  /** Diagnostic — number of distinct paths currently holding a lock
    * record. Used by tests asserting per-path lock independence;
    * ops dashboards can also surface it as a memory-cost signal. */
  def lockCount: Int = locks.size()

  protected def versionOf(target: Path, bytes: Array[Byte]): FileVersion = {
    val mtime = try {
      val attrs = Files.readAttributes(target, classOf[BasicFileAttributes])
      Timestamp(attrs.lastModifiedTime().toMillis)
    } catch {
      case _: Throwable => Timestamp()
    }
    FileVersion(FileVersion.hashOf(bytes), mtime)
  }

  private def resolvePath(path: String): Path = basePath match {
    case Some(base) =>
      val full = base.resolve(path).toAbsolutePath.normalize()
      if (!full.startsWith(base.toAbsolutePath.normalize()))
        throw new SecurityException(s"Path '$path' escapes sandbox: $base")
      full
    case None => Paths.get(path).toAbsolutePath.normalize()
  }

  private def drainStream(in: java.io.InputStream, sb: StringBuilder): Unit = {
    val reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
    try {
      var line = reader.readLine()
      while (line != null && sb.length < OutputTruncationBytes) {
        sb.append(line).append('\n')
        line = reader.readLine()
      }
    } finally reader.close()
  }

  private def isBinary(path: Path): Boolean = {
    try {
      val in = Files.newInputStream(path)
      try {
        val bytes = in.readNBytes(8192)
        bytes.exists(_ == 0)
      } finally in.close()
    } catch {
      case _: Throwable => true  // unreadable → treat as non-text; skip
    }
  }

  /** Skip directories that almost always contain binary cache
    * artifacts (compiled bytecode, build-server indexes, package
    * manager state, version-control metadata). Apps that legitimately
    * need to grep these subclass and override [[searchFiles]]. */
  private val excludedDirNames: Set[String] = Set(
    ".git", ".metals", ".bloop", ".idea", ".vscode",
    "target", "node_modules", "dist", "build", "out",
    ".venv", "venv", "__pycache__", ".gradle", ".mvn"
  )

  private def isInExcludedDir(base: Path, path: Path): Boolean =
    scala.util.Try(base.relativize(path)).toOption.exists { rel =>
      import scala.jdk.CollectionConverters.*
      rel.iterator().asScala.exists(seg => excludedDirNames.contains(seg.toString))
    }

  /** Read a file's lines via a UTF-8 decoder configured to REPLACE
    * malformed sequences with U+FFFD instead of throwing. Survives
    * Windows-1252 / iso-8859-1 / mixed-encoding text files at the
    * cost of an occasional `?` character in the result — acceptable
    * for grep, where the caller is matching a regex, not handing the
    * bytes back unchanged. */
  private def readLinesLenient(path: Path): List[String] = {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
      .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
    val in = Files.newInputStream(path)
    try {
      val reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, decoder))
      val buf = scala.collection.mutable.ListBuffer.empty[String]
      var line = reader.readLine()
      while (line != null) {
        buf += line
        line = reader.readLine()
      }
      buf.toList
    } finally in.close()
  }
}

object LocalFileSystemContext {
  /** Maximum bytes captured per stream (stdout / stderr) for shell
    * commands. Protects against runaway output blowing memory. */
  val OutputTruncationBytes: Int = 100 * 1024
}
