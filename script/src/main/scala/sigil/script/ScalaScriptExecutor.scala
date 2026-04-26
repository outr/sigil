package sigil.script

import dotty.tools.repl.ScriptEngine
import rapid.Task

/**
 * [[ScriptExecutor]] backed by Scala 3's REPL `ScriptEngine`.
 *
 * Bindings are injected via [[ScriptValueHolder]] and a synthetic
 * `val name = ScriptValueHolder.store[Type]` line evaluated in the
 * REPL — this is the trick that lets arbitrary host values flow into
 * Scala scope without compile-time knowledge.
 *
 * **Thread-safety.** All operations synchronize on the engine
 * instance. The REPL's state (imports, prior bindings) persists
 * across calls, so a single `ScalaScriptExecutor` is effectively a
 * stateful session — apps that need session isolation construct one
 * per logical session.
 *
 * **Security.** This executes arbitrary Scala — full JVM access.
 * Apps running untrusted scripts (e.g., user-authored code through a
 * web UI) MUST front this with a sandbox or run remotely via
 * [[sigil.tool.proxy.ProxyTool]] against a dedicated executor
 * process. The framework itself does no sandboxing.
 *
 * Code fences (` ```scala ... ``` `) common in LLM output are
 * stripped before evaluation — saves a layer of "the model wrapped
 * its answer in markdown" parsing.
 */
class ScalaScriptExecutor extends ScriptExecutor {
  private lazy val engine = ScriptEngine()

  override def execute(code: String, bindings: Map[String, Any]): Task[String] =
    executeRaw(code, bindings).map(r => if (r == null) "" else r.toString)

  override def executeRaw(code: String, bindings: Map[String, Any]): Task[Any] = Task {
    synchronized {
      bindAll(bindings)
      val cleaned = stripCodeFences(code)
      engine.eval(cleaned)
    }
  }

  private def bindAll(bindings: Map[String, Any]): Unit =
    bindings.foreach { case (key, value) =>
      ScriptValueHolder.store = value
      val typeName = Option(value.getClass.getCanonicalName).getOrElse(value.getClass.getName)
      engine.eval(s"val $key = sigil.script.ScriptValueHolder.store.asInstanceOf[$typeName]")
      // Force the val to evaluate so the ThreadLocal is read before being overwritten.
      engine.eval(key)
    }

  private def stripCodeFences(code: String): String = code.trim match {
    case s if s.startsWith("```") =>
      val lines = s.linesIterator.toList
      val withoutOpening = lines.drop(1)
      val withoutClosing = if (withoutOpening.lastOption.exists(_.trim == "```")) withoutOpening.dropRight(1) else withoutOpening
      withoutClosing.mkString("\n")
    case s => s
  }
}
