package sigil.script

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}

import java.io.{ByteArrayOutputStream, File, InputStream}
import java.net.URLClassLoader
import java.util.jar.JarFile

import scala.util.boundary
import scala.util.boundary.break

/**
 * Read a symbol's source code from a `-sources.jar` on the executor's
 * classpath. Bug #59 — the deepest level of script-authoring
 * introspection: useful when signature alone (from
 * [[ClassSignaturesTool]]) doesn't make the semantics clear.
 *
 * sbt typically downloads `-sources.jar` artifacts alongside the
 * regular jars for IDE support; this tool exploits that. When no
 * `-sources.jar` ships the symbol's source, returns
 * `(source not available)` and the agent falls back on
 * [[ClassSignaturesTool]].
 *
 * The resolution is whole-file — given `spice.http.client.HttpClient`,
 * we return the contents of `spice/http/client/HttpClient.scala`
 * (or `.java`). Methods inside the file aren't separately extractable
 * without a parser; the agent reads the whole class.
 */
case object ReadSourceTool extends Tool {
  type Input  = ReadSourceInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[ReadSourceInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("read_source")
  val description =
    """Return the source code for a fully-qualified class. Falls back to `(source not available)`
      |when no `-sources.jar` on the classpath ships the symbol.
      |
      |Use this when [[ClassSignaturesTool]]'s parameter-type listing isn't enough to figure
      |out semantics — e.g. understanding what a builder method actually configures, or how
      |a polymorphic API dispatches.""".stripMargin
  override val modes = Set(ScriptAuthoringMode.id)
  override val keywords = Set("source", "code", "read", "scaladoc", "implementation", "introspect")

  override def executeResult(input: ReadSourceInput,
                             context: ToolContext): Task[ToolResult[TextToolOutput]] = Task {
    val text = try render(input.fqn)
    catch { case e: Throwable => s"(read_source failed: ${e.getClass.getSimpleName}: ${e.getMessage})" }
    ToolResult.Success(TextToolOutput(text))
  }

  private def render(fqn: String): String = {
    val normalized = fqn.stripSuffix("$")
    val relPath = normalized.replace('.', '/')
    val candidates = List(s"$relPath.scala", s"$relPath.java")
    findInClasspath(candidates) match {
      case Some((path, body)) => s"# $fqn\n# (source: $path)\n\n$body"
      case None               => s"(source not available for $fqn — no `-sources.jar` on the classpath ships $relPath.scala or .java)"
    }
  }

  private def findInClasspath(candidates: List[String]): Option[(String, String)] = boundary {
    val loader = Thread.currentThread().getContextClassLoader
    var current: ClassLoader = loader
    while (current != null) {
      current match {
        case ucl: URLClassLoader =>
          ucl.getURLs.foreach { url =>
            try {
              val file = new File(url.toURI)
              if (file.isFile && file.getName.endsWith(".jar")) {
                findInJar(file, candidates).foreach(hit => break(Some(hit)))
              } else if (file.isDirectory) {
                findInDir(file, candidates).foreach(hit => break(Some(hit)))
              }
            } catch { case _: Throwable => () }
          }
        case _ => ()
      }
      current = current.getParent
    }
    None
  }

  private def findInJar(file: File, candidates: List[String]): Option[(String, String)] = {
    var jar: JarFile = null
    try {
      jar = new JarFile(file)
      candidates.iterator.flatMap { rel =>
        Option(jar.getEntry(rel)).map { e =>
          val is: InputStream = jar.getInputStream(e)
          val baos = new ByteArrayOutputStream()
          try {
            val buf = new Array[Byte](8192)
            var n = is.read(buf)
            while (n != -1) { baos.write(buf, 0, n); n = is.read(buf) }
          } finally try is.close() catch { case _: Throwable => () }
          (s"${file.getName}!$rel", baos.toString("UTF-8"))
        }
      }.nextOption()
    } catch { case _: Throwable => None }
    finally if (jar != null) try jar.close() catch { case _: Throwable => () }
  }

  private def findInDir(root: File, candidates: List[String]): Option[(String, String)] = {
    candidates.iterator.flatMap { rel =>
      val f = new File(root, rel)
      if (f.isFile) {
        try Some((f.getAbsolutePath, java.nio.file.Files.readString(f.toPath)))
        catch { case _: Throwable => None }
      } else None
    }.nextOption()
  }
}
