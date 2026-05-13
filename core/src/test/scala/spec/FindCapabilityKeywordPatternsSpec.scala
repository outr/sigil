package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.Instructions
import sigil.tool.core.FindCapabilityTool

/**
 * Coverage for sigil bug #129 — `find_capability` instructions used
 * to give the agent zero guidance on *what keywords to use*. Weaker
 * models produced content-laden queries ("find references search
 * symbol password reset" — mixing tool-shape with project content)
 * that scored badly against the registry's keyword-ranker.
 *
 * The fix is pure instruction text: discovery-query patterns by
 * intent live in the system prompt's `DefaultToolsGuidance` and
 * `PureDiscoveryToolsGuidance` sections (single source of truth, post
 * core-tool dedup). The tool description points the model at the
 * system prompt's templates section rather than restating them.
 * Behavioral coverage (agent produces good queries) lives on the
 * live-llama specs against the published prompt.
 */
class FindCapabilityKeywordPatternsSpec extends AnyWordSpec with Matchers {

  // Each intent template lists the verb/category atoms the bug
  // surfaced as effective tool-shape signals; the prompt must
  // anchor at least one of each so the model has a seed for that
  // discovery path.
  private val expectedIntents: List[(String, List[String])] = List(
    "Read a file's contents"         -> List("view", "file", "source", "contents", "read", "code"),
    "Search files for a pattern"     -> List("grep", "search", "find", "text", "pattern", "match"),
    "List files / discover paths"    -> List("glob", "files", "directory", "paths", "list"),
    "Run a shell command"            -> List("bash", "shell", "command", "execute", "run"),
    "Navigate code symbols"          -> List("lsp", "definition", "reference", "symbol"),
    "Edit / modify a file"           -> List("edit", "modify", "update", "file", "patch"),
    "Web / HTTP fetch"               -> List("http", "fetch", "download", "url"),
    "Switch the model"               -> List("model", "switch", "pin", "change"),
    "Save / recall memory"           -> List("memory", "save", "recall", "persist"),
    "Schedule / wait / time"         -> List("sleep", "wait", "delay", "timer", "schedule")
  )

  "Instructions.DefaultToolsGuidance" should {
    "label `find_capability` as a tool-shape search, not content search" in {
      Instructions.DefaultToolsGuidance should include("TOOL-SHAPE search")
      Instructions.DefaultToolsGuidance should include("not a CONTENT search")
    }

    "ship every intent template's anchor keyword" in {
      expectedIntents.foreach { case (intent, atoms) =>
        withClue(s"intent '$intent' must seed at least one anchor keyword: ") {
          atoms.exists(Instructions.DefaultToolsGuidance.toLowerCase.contains) shouldBe true
        }
      }
    }

    "include the bad-vs-good query worked example" in {
      // The negative example from the wire log — kept verbatim so
      // the model can pattern-match its own failure mode.
      Instructions.DefaultToolsGuidance should include("password reset")
      Instructions.DefaultToolsGuidance should include("lsp reference")
    }
  }

  "Instructions.PureDiscoveryToolsGuidance" should {
    "carry the tool-shape vs content distinction" in {
      Instructions.PureDiscoveryToolsGuidance should include("TOOL-SHAPE")
    }

    "include at least three intent-anchor keyword groups" in {
      // PureDiscovery prompt is condensed; doesn't need every
      // template but must anchor the major shapes.
      val matched = expectedIntents.count { case (_, atoms) =>
        atoms.exists(Instructions.PureDiscoveryToolsGuidance.toLowerCase.contains)
      }
      matched should be >= 3
    }
  }

  "FindCapabilityTool.description" should {
    "point the model at the system prompt's templates section" in {
      // Single source of truth: the templates live in the system prompt;
      // the tool description references it rather than restating.
      FindCapabilityTool.description should include("Discovery-query patterns")
    }

    "describe `keywords` as action-shape, not content" in {
      // The terse argument-shape rule that pairs with the system prompt's
      // longer treatment. Wording is "action SHAPE" instead of the system
      // prompt's "TOOL-SHAPE" — both convey the same constraint.
      FindCapabilityTool.description should (include("action SHAPE") and include("not project content"))
    }
  }
}
