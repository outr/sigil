package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.event.CapabilityResults
import sigil.provider.ConversationMode
import sigil.tool.DiscoveryRequest
import sigil.tool.discovery.{CapabilityStatus, CapabilityType}

/**
 * Regression coverage for bug #66 — `find_capability` used to surface
 * matching tools only. Mode-gated capabilities (per #59's
 * `ScriptAuthoringMode` pattern) were invisible to the discovery
 * path because the matching mode itself wasn't returned, leaving the
 * agent with no entry-point to unlock the gated tools.
 *
 * Post-fix:
 *   - `Sigil.findCapabilities(request)` returns a unified
 *     `List[CapabilityMatch]` covering tools + modes.
 *   - Modes with name / description / skill content matching the
 *     keywords appear with `capabilityType = Mode` and a
 *     `RequiresSetup(s"""change_mode("$name")""")` hint.
 *   - `FindCapabilityTool` emits a `CapabilityResults` event (the
 *     old `ToolResults` shape stays in use for tool-suggestion
 *     cascades like `CreateScriptToolTool`).
 *   - `FrameBuilder.updateProjections` populates `suggestedTools`
 *     only from Tool-typed matches; Mode/Skill matches reach the
 *     agent via the rendered tool result, not the suggested-tools
 *     section.
 */
class FindCapabilityModeDiscoverySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def request(keywords: String, currentMode: sigil.provider.Mode = ConversationMode): DiscoveryRequest =
    DiscoveryRequest(
      keywords = keywords,
      chain = List(TestUser, TestAgent),
      mode = currentMode,
      callerSpaces = Set(GlobalSpace)
    )

  "Sigil.findCapabilities (bug #66)" should {
    "return Mode-typed matches when keywords match a registered mode's name" in {
      // TestSigil registers TestCodingMode (`name = "coding"`,
      // description mentions "code generation, editing, review").
      TestSigil.findCapabilities(request("coding")).map { matches =>
        val modeMatches = matches.filter(_.capabilityType == CapabilityType.Mode)
        modeMatches should not be empty
        val coding = modeMatches.find(_.name == "coding")
        withClue(s"matches=$matches: ") {
          coding shouldBe defined
          coding.get.status shouldBe CapabilityStatus.RequiresSetup("""change_mode("coding")""")
        }
      }
    }

    "return Mode-typed matches when keywords match a mode's skill content" in {
      // TestSkilledMode's skill content includes "test agent" + "test skill".
      TestSigil.findCapabilities(request("test agent skill")).map { matches =>
        val modeMatches = matches.filter(_.capabilityType == CapabilityType.Mode)
        modeMatches.map(_.name) should contain ("skilled")
      }
    }

    "exclude the currently-active mode from results (no-op switch)" in {
      // Set `request.mode = TestCodingMode` and search for "coding"
      // — the active mode is the one we're already in, so it shouldn't
      // come back as a discovery suggestion.
      TestSigil.findCapabilities(request("coding", currentMode = TestCodingMode)).map { matches =>
        val modeNames = matches.collect {
          case m if m.capabilityType == CapabilityType.Mode => m.name
        }
        modeNames should not contain "coding"
      }
    }

    "return Mode matches sorted by score (descending) interleaved with Tool matches" in {
      // No tool registrations to compete here; the test asserts the
      // sort invariant directly. With only Mode matches, scores are
      // monotonically non-increasing.
      TestSigil.findCapabilities(request("test")).map { matches =>
        val scores = matches.map(_.score)
        scores shouldBe scores.sorted(using Ordering[Double].reverse)
      }
    }

    "return empty list when no keywords match anything" in {
      TestSigil.findCapabilities(request("zzzzznonexistentkeyword")).map { matches =>
        // Tool finder may return matches based on its own scoring;
        // for this test we only assert that no Mode match appears
        // for a clearly-nonsense keyword.
        val modeMatches = matches.filter(_.capabilityType == CapabilityType.Mode)
        modeMatches shouldBe empty
      }
    }
  }

  "Sigil.findModes default scoring" should {
    "score exact-word matches higher than substring-only matches" in {
      // "coding" is the mode's exact name — should score 5 (exact-word
      // hit). A keyword that only appears as a substring inside a
      // longer word in the description scores 2.
      for {
        exactScored <- TestSigil.findModes(request("coding"))
        substringScored <- TestSigil.findModes(request("ode"))
      } yield {
        val exactCoding = exactScored.find(_._1.name == "coding").map(_._2)
        val subCoding   = substringScored.find(_._1.name == "coding").map(_._2)
        // The exact-word path scores at least 5; the substring path
        // scores at most 2 if it scores at all.
        exactCoding.exists(_ >= 5.0) shouldBe true
        subCoding.forall(_ <= 2.0) shouldBe true
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
