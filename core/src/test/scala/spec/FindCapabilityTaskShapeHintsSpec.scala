package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.ToolName
import sigil.tool.discovery.{CapabilityMatch, CapabilityStatus, CapabilityType, TaskShapeHints}

/**
 * Regression for sigil bug #283 — `find_capability`'s output augments
 * raw BM25 matches with task-SHAPE hints derived from the composition
 * of the returned tools plus the query verbs. Lets the agent see the
 * better-fit primitive (e.g. `dispatch_workers` for multi-file
 * transformations) even when BM25 ranks a textual tool (`grep`) at
 * the top against grep-shaped query tokens.
 */
class FindCapabilityTaskShapeHintsSpec extends AnyWordSpec with Matchers {

  private def toolMatch(name: String, score: Double = 1.0): CapabilityMatch =
    CapabilityMatch(
      name           = name,
      description    = s"Description for $name",
      capabilityType = CapabilityType.Tool,
      score          = score,
      status         = CapabilityStatus.Ready
    )

  "TaskShapeHints.synthesize" should {

    "fire a multi-file-transformation hint when grep + dispatch_workers both rank for a remove-across-files query" in {
      val matches = List(
        toolMatch("grep",             score = 12.3),
        toolMatch("dispatch_workers", score = 9.8),
        toolMatch("edit_file",        score = 8.1)
      )
      val hints = TaskShapeHints.synthesize("remove all bug references across files", matches)
      hints.map(_.shape) should contain ("multi_file_transformation")
      val hint = hints.find(_.shape == "multi_file_transformation").get
      hint.recommended shouldBe ToolName("dispatch_workers")
      hint.context should include ("multi-file transformation")
      hint.context should include ("dispatch_workers")
    }

    "NOT fire the multi-file-transformation hint when dispatch_workers is absent from the result set" in {
      val matches = List(
        toolMatch("grep",      score = 12.3),
        toolMatch("edit_file", score = 8.1)
      )
      val hints = TaskShapeHints.synthesize("remove all bug references across files", matches)
      hints.map(_.shape) should not contain "multi_file_transformation"
    }

    "NOT fire the multi-file-transformation hint when the query lacks transform verbs" in {
      val matches = List(
        toolMatch("grep",             score = 12.3),
        toolMatch("dispatch_workers", score = 9.8)
      )
      // Pure search shape — no "do X to many things" verb.
      val hints = TaskShapeHints.synthesize("grep search find text pattern match", matches)
      hints.map(_.shape) should not contain "multi_file_transformation"
    }

    "fire a semantic-navigation hint when lsp_find_references + grep both rank for a navigation query" in {
      val matches = List(
        toolMatch("grep",                score = 10.0),
        toolMatch("lsp_find_references", score = 8.5)
      )
      val hints = TaskShapeHints.synthesize("find callers of method foo across the codebase", matches)
      hints.map(_.shape) should contain ("semantic_navigation")
      val hint = hints.find(_.shape == "semantic_navigation").get
      hint.recommended shouldBe ToolName("lsp_find_references")
      hint.context should include ("lsp_find_references")
    }

    "NOT fire the semantic-navigation hint when no LSP nav tool is in the result set" in {
      val matches = List(
        toolMatch("grep",      score = 10.0),
        toolMatch("read_file", score = 5.0)
      )
      val hints = TaskShapeHints.synthesize("find callers of method foo", matches)
      hints.map(_.shape) should not contain "semantic_navigation"
    }

    "return an empty list when no recognised shape applies" in {
      val matches = List(
        toolMatch("respond"),
        toolMatch("no_response")
      )
      TaskShapeHints.synthesize("hello world", matches) shouldBe empty
    }

    "ignore non-Tool matches when checking the composition" in {
      // Mode / Skill matches don't count toward the textual-primitive
      // OR dispatch_workers presence check — synthesis is about which
      // TOOLS the agent can call directly.
      val matches = List(
        toolMatch("grep"),
        CapabilityMatch(
          name           = "dispatch_workers",
          description    = "a mode, not the tool",
          capabilityType = CapabilityType.Mode,
          score          = 1.0,
          status         = CapabilityStatus.Ready
        )
      )
      val hints = TaskShapeHints.synthesize("remove all bug references across files", matches)
      hints.map(_.shape) should not contain "multi_file_transformation"
    }
  }
}
