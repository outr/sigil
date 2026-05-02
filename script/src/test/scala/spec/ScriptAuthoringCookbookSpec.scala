package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.script.{ScalaScriptExecutor, ScriptAuthoringMode, ScriptCompileException}

/**
 * Regression / drift guard for bug #70 — `ScriptAuthoringMode`'s
 * skill cookbook has the static text the agent reads when authoring a
 * script. If those snippets don't actually compile against the
 * executor's prelude (the real Spice / Fabric / Rapid versions on the
 * classpath), the agent reads the wrong shapes, copies them
 * verbatim, and burns iterations chasing compile errors that the
 * cookbook itself caused.
 *
 * This spec extracts every fenced code block from the skill content
 * and runs each through [[ScalaScriptExecutor]] to verify it
 * compiles. Compile success = the snippet's API shapes match the
 * actual library signatures. Compile failure = the cookbook has
 * drifted; the diff fails this test before it reaches the agent.
 *
 * Each snippet is wrapped as `def run(): Any = { … }` inside a
 * synthetic object before evaluation — the wrapper turns into a
 * compile-only check that doesn't actually execute the snippet
 * (so network calls etc. don't fire). Compile errors throw
 * [[ScriptCompileException]] (per bug #55's diagnostic capture);
 * non-compile errors (NoClassDef, runtime throws from somehow
 * triggering execution) pass — the snippet COMPILED, which is what
 * the test asserts.
 *
 * Pin: when Spice / Fabric / Rapid evolve and a cookbook snippet
 * stops compiling against the new signatures, this test fails. Fix
 * the cookbook in `ScriptAuthoringMode.skill.content`.
 */
class ScriptAuthoringCookbookSpec extends AnyWordSpec with Matchers {

  /** Pull every fenced code block (` ```...``` `) out of the skill
    * content. The cookbook uses unmarked fences in the form
    * ```
    * ```
    * code…
    * ```
    * ``` — three-backtick on its own line opens, three-backtick on
    * its own line closes. Returns the inner code (lines between the
    * fences, joined). */
  private def extractFencedSnippets(content: String): List[String] = {
    val out = scala.collection.mutable.ListBuffer.empty[String]
    val acc = scala.collection.mutable.ListBuffer.empty[String]
    var inFence = false
    content.linesIterator.foreach { line =>
      val trimmed = line.trim
      if (trimmed.startsWith("```")) {
        if (inFence) {
          out += acc.mkString("\n")
          acc.clear()
          inFence = false
        } else {
          inFence = true
        }
      } else if (inFence) {
        acc += line
      }
    }
    out.toList
  }

  "ScriptAuthoringMode skill cookbook (bug #70)" should {
    val skill = ScriptAuthoringMode.skill.getOrElse(
      fail("ScriptAuthoringMode.skill must be defined — the cookbook lives there.")
    )
    val snippets = extractFencedSnippets(skill.content)

    "extract at least one fenced snippet from the skill content" in {
      snippets should not be empty
    }

    "compile every cookbook snippet against the actual executor classpath" in {
      val executor = new ScalaScriptExecutor

      // Snippets that reference script bindings (`args`, `context`)
      // or app-supplied helpers (`someJavaApi`, `secrets`) won't
      // resolve in a bare REPL. Either inject test fixtures at the
      // wrapper level OR skip those specific snippets. We inject
      // a permissive fixture: `args` is `fabric.obj()`, `context`
      // is `null` (typed as Any so reference-only snippets type-
      // check), `someJavaApi` returns an empty Java list. Snippets
      // that try to actually USE these will fail at runtime, but the
      // compile-only check just needs the names in scope.
      val preamble =
        """val args: fabric.Json = fabric.obj()
          |val context: Any = null
          |def someJavaApi(): java.util.List[String] = java.util.Collections.emptyList()
          |""".stripMargin

      snippets.zipWithIndex.foreach { case (raw, i) =>
        val snippetNumber = i + 1
        // Wrap in a method body so the snippet is compile-checked
        // but not executed (no network calls, no side effects).
        val wrapped =
          s"""object Snippet$snippetNumber {
             |  def run(): Any = {
             |$preamble
             |${raw.linesIterator.map("    " + _).mkString("\n")}
             |  }
             |}""".stripMargin

        withClue(s"Cookbook snippet #$snippetNumber failed to compile:\n--- snippet ---\n$raw\n--- wrapped ---\n$wrapped\n--- end ---\n") {
          // Evaluate; what matters is that a ScriptCompileException
          // does NOT escape. The synthetic object's `run()` is
          // declared but not called, so no runtime side effects fire.
          try {
            executor.executeRaw(wrapped, Map.empty).sync()
            // success — snippet compiled
            succeed
          } catch {
            case e: ScriptCompileException =>
              fail(
                s"Snippet #$snippetNumber failed to compile against the prelude.\n" +
                  s"--- compiler diagnostic ---\n${e.getMessage}\n--- end ---"
              )
            case _: Throwable =>
              // Non-compile error (NoClassDef, ClassCastException,
              // etc.) — the snippet's bytecode existed (= it
              // compiled), and the runtime issue is irrelevant for a
              // cookbook compile check.
              succeed
          }
        }
      }
      succeed
    }
  }
}
