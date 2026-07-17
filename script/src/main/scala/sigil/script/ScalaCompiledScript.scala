package sigil.script

import dotty.tools.repl.ScriptEngine
import fabric.{Json, Str}
import fabric.io.JsonParser
import rapid.Task

import java.io.ByteArrayOutputStream

/**
 * [[CompiledScript]] backed by a Scala 3 REPL `def` compiled exactly
 * once by [[ScalaScriptExecutor.compile]]. The user source is wrapped
 * in a uniquely-named top-level `def` whose parameters are the
 * declared bindings; that `def` persists in the REPL's namespace, so
 * every [[invoke]] only has to re-bind argument values and call it.
 *
 * `invoke` is parallel-safe — concurrent worker fibers may call it at
 * once. All REPL interaction synchronizes on the shared engine, so
 * the dispatcher gets one compile and N serialized-but-cheap calls.
 */
final class ScalaCompiledScript(engine: ScriptEngine,
                                captured: ByteArrayOutputStream,
                                defName: String,
                                bindingTypes: List[ScriptBinding])
  extends CompiledScript {

  override def invoke(bindings: Map[String, Any]): Task[Either[String, Json]] = Task {
    engine.synchronized {
      try {
        bindingTypes.foreach { binding =>
          val value = bindings.getOrElse(
            binding.name,
            throw new RuntimeException(s"compiled script invocation missing binding '${binding.name}'"))
          ScriptValueHolder.store = value
          engine.eval(s"val ${binding.name} = sigil.script.ScriptValueHolder.store.asInstanceOf[${binding.typeName}]")
          engine.eval(binding.name)
        }
        captured.reset()
        val args = bindingTypes.map(_.name).mkString(", ")
        val result = engine.eval(s"$defName($args)")
        val diagnostics = captured.toString.trim
        if (ScalaCompiledScript.containsErrorDiagnostic(diagnostics)) {
          Left(diagnostics): Either[String, Json]
        } else {
          Right(ScalaCompiledScript.toJson(result))
        }
      } catch {
        case t: Throwable =>
          Left(ScalaCompiledScript.rootMessage(t)): Either[String, Json]
      }
    }
  }
}

object ScalaCompiledScript {

  /**
   * Encode an arbitrary script return value as fabric Json. A `Json`
   * value passes through; a `String` is JSON-parsed (or wrapped as a
   * JSON string when it is not valid JSON); any other value is
   * stringified and parsed the same way. `null` becomes
   * `fabric.Null`.
   */
  def toJson(value: Any): Json = value match {
    case null => fabric.Null
    case j: Json => j
    case s: String => scala.util.Try(JsonParser(s)).getOrElse(Str(s))
    case other =>
      val rendered = other.toString
      scala.util.Try(JsonParser(rendered)).getOrElse(Str(rendered))
  }

  /**
   * Unwrap reflective invocation wrappers
   * (`InvocationTargetException`, `ExceptionInInitializerError`) to
   * the root cause's message, so a runtime throw inside the compiled
   * `def` surfaces the script-relevant message rather than the JVM's
   * reflection-layer wrapper name. Falls back to the wrapper's simple
   * class name when no message is available.
   */
  def rootMessage(t: Throwable): String = {
    var current: Throwable = t
    while (current.getCause != null && current.getCause != current) current = current.getCause
    Option(current.getMessage).filter(_.nonEmpty).getOrElse(current.getClass.getSimpleName)
  }

  /**
   * True when the captured REPL output carries a Scala 3 error
   * diagnostic. Mirrors [[ScalaScriptExecutor]]'s detection so a
   * runtime-surfaced compile error (e.g. an exception inside the
   * compiled `def`) is still reported. Warnings are not triggers.
   */
  def containsErrorDiagnostic(out: String): Boolean =
    if (out.isEmpty) false
    else out.linesIterator.exists(l => l.contains("-- [E") || l.contains(" error:"))
}
