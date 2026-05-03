package sigil.script

import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.zip.ZipException

/**
 * Fuzzy-resolve an unqualified symbol (`HttpClient`, `JsonParser`,
 * `Task.map`) to one or more fully-qualified candidates on the
 * executor's classpath. Bug #59 — first step of the script-authoring
 * lookup chain: `library_lookup → class_signatures → write code`.
 *
 * Implementation: walks the URLs of every `URLClassLoader` ancestor
 * of the context classloader, lists `.class` entries from each jar,
 * matches the simple class name against the requested symbol (case-
 * insensitive), and returns the FQN candidates. For method-style
 * lookups (`Task.map`), the receiver before the dot is fuzzy-matched
 * and the surviving candidates' methods are filtered to those whose
 * name matches the part after the dot.
 *
 * Returns at most 25 candidates — high-cardinality matches (e.g. a
 * lone "C" against a 5k-jar classpath) get truncated rather than
 * flooding the agent's context.
 */
case object LibraryLookupTool extends TypedTool[LibraryLookupInput](
  name = ToolName("library_lookup"),
  description =
    """Fuzzy-resolve an unqualified class name or method reference to its fully-qualified
      |form(s) on the executor's classpath. Use this BEFORE writing code that touches an API
      |you're unsure about — one round-trip beats one failed compile.
      |
      |Examples: `library_lookup("HttpClient")` →
      |`spice.http.client.HttpClient`. `library_lookup("Task.map")` → `rapid.Task.map`
      |(with the matching method enumerated).
      |
      |Returns up to 25 candidates. After picking the right one, call `class_signatures(fqn)`
      |for the full method/field surface.""".stripMargin,
  modes = Set(ScriptAuthoringMode.id),
  keywords = Set("lookup", "find", "symbol", "class", "method", "fqn", "library", "api")
) {

  private val MaxCandidates = 25

  override protected def executeTyped(input: LibraryLookupInput, context: TurnContext): rapid.Stream[Event] = {
    val text = try render(input.symbol)
    catch { case e: Throwable => s"(lookup failed: ${e.getClass.getSimpleName}: ${e.getMessage})" }
    rapid.Stream.emit(reply(context, text))
  }

  private def render(symbol: String): String = {
    val (classPart, methodPart) = symbol.split('.').toList match {
      case Nil          => ("", None)
      case single :: Nil => (single, None)
      case many          =>
        // Heuristic: if the last segment starts lower-case, treat it as
        // a method (e.g. `Task.map`); otherwise it's a nested class
        // path and we match the whole thing as the receiver.
        val last = many.last
        if (last.headOption.exists(_.isLower)) (many.dropRight(1).mkString("."), Some(last))
        else (symbol, None)
    }
    if (classPart.isEmpty) return "(empty symbol)"

    val classes = scanClasspath().filter(matches(_, classPart))
    if (classes.isEmpty) {
      s"(no matches for '$symbol' on the executor classpath)"
    } else {
      val capped = classes.take(MaxCandidates)
      val truncated = classes.size > MaxCandidates
      val sb = new StringBuilder
      sb.append(s"# Candidates for '$symbol'\n")
      capped.foreach { fqn =>
        sb.append(s"\n- $fqn")
        methodPart match {
          case Some(m) => appendMatchingMethods(sb, fqn, m)
          case None    => ()
        }
      }
      if (truncated) sb.append(s"\n\n(${classes.size - MaxCandidates} additional matches truncated; refine the symbol)")
      sb.toString
    }
  }

  /** Append matching method signatures under a candidate. Silent on
    * load failure — a hit on the class name is still useful even if
    * we couldn't reflect on it. */
  private def appendMatchingMethods(sb: StringBuilder, fqn: String, methodName: String): Unit = {
    val cls = try Class.forName(fqn)
    catch { case _: Throwable =>
      try Class.forName(fqn + "$")
      catch { case _: Throwable => return }
    }
    val matching = cls.getDeclaredMethods.toList
      .filter(m => java.lang.reflect.Modifier.isPublic(m.getModifiers))
      .filterNot(_.isSynthetic)
      .filter(_.getName.equalsIgnoreCase(methodName))
    if (matching.nonEmpty) {
      matching.sortBy(_.getName).foreach { m =>
        val params = m.getParameterTypes.toList.map(simpleName).mkString(", ")
        val ret = simpleName(m.getReturnType)
        sb.append(s"\n    ${m.getName}($params): $ret")
      }
    }
  }

  private def simpleName(t: Class[?]): String = {
    val n = t.getName
    val short = n.substring(n.lastIndexOf('.') + 1).replace('$', '.')
    if (t.isArray) s"Array[${simpleName(t.getComponentType)}]" else short
  }

  /** Case-insensitive match of the simple class name (last `.`-segment,
    * `$`-stripped) against the symbol. Trailing `$` on the FQN means a
    * Scala module — match the bare name. */
  private def matches(fqn: String, symbol: String): Boolean = {
    val simple = fqn.substring(fqn.lastIndexOf('.') + 1).stripSuffix("$")
    simple.equalsIgnoreCase(symbol)
  }

  /** Walk the context classloader chain and gather every `.class`
    * entry's FQN. Each invocation scans fresh — script-authoring is
    * an interactive flow, not a hot path. Falls back to
    * `java.class.path` when the loader chain has no URLClassLoader
    * ancestors (sbt 1 worker JVMs, fat-jar launches, jlink images). */
  private def scanClasspath(): List[String] = {
    val loader = Thread.currentThread().getContextClassLoader
    val out = collection.mutable.LinkedHashSet.empty[String]
    var sawUrlLoader = false
    var current: ClassLoader = loader
    while (current != null) {
      current match {
        case ucl: URLClassLoader =>
          sawUrlLoader = true
          ucl.getURLs.foreach { url =>
            try gatherEntry(new File(url.toURI), out)
            catch { case _: Throwable => () }
          }
        case _ => ()
      }
      current = current.getParent
    }
    if (!sawUrlLoader) {
      Option(System.getProperty("java.class.path")).filter(_.nonEmpty).foreach { cp =>
        cp.split(java.io.File.pathSeparator).foreach { entry =>
          try gatherEntry(new File(entry), out)
          catch { case _: Throwable => () }
        }
      }
    }
    out.toList
  }

  private def gatherEntry(file: File, out: collection.mutable.LinkedHashSet[String]): Unit = {
    if (file.isFile && file.getName.endsWith(".jar")) gatherJar(file, out)
    else if (file.isDirectory) gatherDir(file, file, out)
  }

  private def gatherJar(file: File, out: collection.mutable.LinkedHashSet[String]): Unit = {
    var jar: JarFile = null
    try {
      jar = new JarFile(file)
      val entries = jar.entries()
      while (entries.hasMoreElements) {
        val e = entries.nextElement()
        val name = e.getName
        if (!e.isDirectory && name.endsWith(".class") && !name.contains("$$") && !name.startsWith("META-INF")) {
          out += name.stripSuffix(".class").replace('/', '.')
        }
      }
    } catch {
      case _: ZipException => ()
      case _: Throwable    => ()
    } finally if (jar != null) try jar.close() catch { case _: Throwable => () }
  }

  private def gatherDir(root: File, current: File, out: collection.mutable.LinkedHashSet[String]): Unit = {
    val children = current.listFiles()
    if (children != null) children.foreach { child =>
      if (child.isDirectory) gatherDir(root, child, out)
      else if (child.getName.endsWith(".class") && !child.getName.contains("$$")) {
        val rel = root.toURI.relativize(child.toURI).getPath
        out += rel.stripSuffix(".class").replace('/', '.')
      }
    }
  }

  private def reply(context: TurnContext, text: String): Message =
    Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(text)),
      state          = EventState.Complete,
      role           = MessageRole.Tool,
      visibility     = MessageVisibility.Agents
    )
}
