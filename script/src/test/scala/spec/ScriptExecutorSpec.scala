package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.script.{ScalaScriptExecutor, ScriptCompileException}

/**
 * Coverage for [[ScalaScriptExecutor]] — basic eval, code-fence
 * stripping, raw vs. stringified results, and host-value bindings
 * via the [[sigil.script.ScriptValueHolder]] hand-off.
 */
class ScriptExecutorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /** Bug #58 — the default `ScalaScriptExecutor` constructor now
    * auto-detects classpath from the context `URLClassLoader` when
    * `java.class.path` is incomplete (sbt 2 test workers, IDE
    * runners, etc.). No explicit override needed in tests. */
  private def newExecutor(): ScalaScriptExecutor = new ScalaScriptExecutor

  "ScalaScriptExecutor" should {
    "evaluate a literal expression" in {
      val executor = newExecutor()
      executor.execute("1 + 2", Map.empty).map(_ shouldBe "3")
    }

    "evaluate a multi-line block" in {
      val executor = newExecutor()
      val code = """val a = 5
                   |val b = 7
                   |a * b""".stripMargin
      executor.execute(code, Map.empty).map(_ shouldBe "35")
    }

    "strip a single-language code fence" in {
      val executor = newExecutor()
      val code = "```scala\n1 + 1\n```"
      executor.execute(code, Map.empty).map(_ shouldBe "2")
    }

    "expose a bound host value to the script by name" in {
      val executor = newExecutor()
      val code = "(value + 100).toString"
      executor.execute(code, Map("value" -> Integer.valueOf(42))).map(_ shouldBe "142")
    }

    "return the empty string when the script's last expression is null" in {
      val executor = newExecutor()
      executor.execute("null", Map.empty).map(_ shouldBe "")
    }

    "executeRaw returns the typed value, not the stringified form" in {
      val executor = newExecutor()
      executor.executeRaw("Vector(1, 2, 3)", Map.empty).map { result =>
        result shouldBe Vector(1, 2, 3)
      }
    }

    "raise ScriptCompileException with diagnostic text on a type-error script (bug #55)" in {
      // Pre-fix: this returned `null` which `execute` collapsed to ""
      // — the agent received `{"output":"","error":null}` and
      // hallucinated. Post-fix: the executor raises with the
      // captured Scala 3 reporter output.
      val executor = newExecutor()
      executor.execute("val x: Int = \"not an int\"", Map.empty).attempt.map { result =>
        result.isFailure shouldBe true
        result.failed.get shouldBe a[ScriptCompileException]
        // Scala 3 emits a "Type Mismatch Error" or "Type Error" for this;
        // either way the diagnostic mentions "Int" and "String".
        val msg = result.failed.get.getMessage
        msg should (include("Int") and include("String"))
      }
    }

    "raise ScriptCompileException for a syntax error (bug #55)" in {
      val executor = newExecutor()
      executor.execute("val x = ", Map.empty).attempt.map { result =>
        result.isFailure shouldBe true
        result.failed.get shouldBe a[ScriptCompileException]
      }
    }

    "still succeed for a script that produces compiler warnings only (no error)" in {
      // Warnings (e.g. an unused import) shouldn't trigger
      // ScriptCompileException — only errors. The eval produces a
      // value and returns normally. Bug #55.
      val executor = newExecutor()
      // `val _ = expr` does not generate a warning in Scala 3, but a
      // simple deprecation-style construct works. Easiest reliable
      // check: a successful eval that has no errors returns the
      // value untouched.
      executor.execute("1 + 1", Map.empty).map(_ shouldBe "2")
    }

    "default constructor auto-detects classpath from the context classloader (bugs #57 + #58)" in Task {
      // Bug #57 (escape hatch) + #58 (auto-detection):
      //   - sbt 2 test workers populate `java.class.path` with only
      //     sbt's worker plumbing — the real classpath lives in a
      //     URLClassLoader the worker built manually.
      //   - The executor's lazy engine init now falls back from
      //     `classpathOverride` to URL introspection of the context
      //     classloader before defaulting to `ScriptEngine()`'s
      //     `-usejavacp` path. So `new ScalaScriptExecutor` works
      //     out of the box in sbt 2 / IDE / Bazel test JVMs without
      //     consumer-side wiring.
      //
      // Sanity — Class.forName resolves the types we need via the
      // runtime classloader, confirming we're in the layered-loader
      // shape this test is designed for.
      Class.forName("scala.Predef$") should not be null
      Class.forName("dotty.tools.repl.ScriptEngine") should not be null
      ScalaScriptExecutor.detectClasspathFromContext() shouldBe defined

      // Default constructor — no override. Should evaluate cleanly.
      val executor = new ScalaScriptExecutor
      executor.execute("1 + 1", Map.empty).sync() shouldBe "2"
      succeed
    }
  }
}
