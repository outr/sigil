package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.core.FindCapabilityTool

/**
 * Coverage for sigil bug #129 — `find_capability` instructions used
 * to give the agent zero guidance on *what keywords to use*. Weaker
 * models produced content-laden queries ("find references search
 * symbol password reset" — mixing tool-shape with project content)
 * that scored badly against the registry's keyword-ranker.
 *
 * The fix is pure instruction text. The discovery-query patterns are a
 * `find_capability`-specific concern, so they live in the tool's own
 * `description` (sent to the model whenever the tool is in the roster)
 * rather than in the system prompt, which stays tool-agnostic.
 * Behavioral coverage (agent produces good queries) lives on the
 * live-llama specs against the published description.
 */
class FindCapabilityKeywordPatternsSpec extends AnyWordSpec with Matchers {

  // Each intent template lists the verb/category atoms the bug
  // surfaced as effective tool-shape signals; the description must
  // anchor at least one of each so the model has a seed for that
  // discovery path.
  private val expectedIntents: List[(String, List[String])] = List(
    "Read a file's contents" -> List("view", "file", "source", "contents", "read", "code"),
    "Search files for a pattern" -> List("grep", "search", "find", "text", "pattern", "match"),
    "List files / discover paths" -> List("glob", "files", "directory", "paths", "list"),
    "Run a shell command" -> List("bash", "shell", "command", "execute", "run"),
    "Navigate code symbols" -> List("lsp", "definition", "reference", "symbol"),
    "Edit / modify a file" -> List("edit", "modify", "update", "file", "patch"),
    "Web / HTTP fetch" -> List("http", "fetch", "download", "url"),
    "Switch the model" -> List("model", "switch", "pin", "change"),
    "Save / recall memory" -> List("memory", "save", "recall", "persist"),
    "Schedule / wait / time" -> List("sleep", "wait", "delay", "timer", "schedule")
  )

  "FindCapabilityTool.description" should {
    "label the search as tool-shape, not content" in {
      FindCapabilityTool.description should include("TOOL-SHAPE search")
      FindCapabilityTool.description should include("not a CONTENT search")
    }

    "describe `keywords` as action-shape, not content" in {
      FindCapabilityTool.description should (include("action SHAPE") and include("not project content"))
    }

    "ship every intent template's anchor keyword" in
      expectedIntents.foreach { case (intent, atoms) =>
        withClue(s"intent '$intent' must seed at least one anchor keyword: ") {
          atoms.exists(FindCapabilityTool.description.toLowerCase.contains) shouldBe true
        }
      }

    "include the bad-vs-good query worked example" in {
      // The negative example from the wire log — kept verbatim so
      // the model can pattern-match its own failure mode.
      FindCapabilityTool.description should include("password reset")
      FindCapabilityTool.description should include("lsp reference")
    }
  }
}
