package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.script.ScalaScriptExecutor

/**
 * Coverage for [[ScalaScriptExecutor]] — basic eval, code-fence
 * stripping, raw vs. stringified results, and host-value bindings
 * via the [[sigil.script.ScriptValueHolder]] hand-off.
 */
class ScriptExecutorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  "ScalaScriptExecutor" should {
    "evaluate a literal expression" in {
      val executor = new ScalaScriptExecutor
      executor.execute("1 + 2", Map.empty).map(_ shouldBe "3")
    }

    "evaluate a multi-line block" in {
      val executor = new ScalaScriptExecutor
      val code = """val a = 5
                   |val b = 7
                   |a * b""".stripMargin
      executor.execute(code, Map.empty).map(_ shouldBe "35")
    }

    "strip a single-language code fence" in {
      val executor = new ScalaScriptExecutor
      val code = "```scala\n1 + 1\n```"
      executor.execute(code, Map.empty).map(_ shouldBe "2")
    }

    "expose a bound host value to the script by name" in {
      val executor = new ScalaScriptExecutor
      val code = "(value + 100).toString"
      executor.execute(code, Map("value" -> Integer.valueOf(42))).map(_ shouldBe "142")
    }

    "return the empty string when the script's last expression is null" in {
      val executor = new ScalaScriptExecutor
      executor.execute("null", Map.empty).map(_ shouldBe "")
    }

    "executeRaw returns the typed value, not the stringified form" in {
      val executor = new ScalaScriptExecutor
      executor.executeRaw("Vector(1, 2, 3)", Map.empty).map { result =>
        result shouldBe Vector(1, 2, 3)
      }
    }
  }
}
