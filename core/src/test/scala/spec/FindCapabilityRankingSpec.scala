package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.SpaceId
import sigil.provider.ConversationMode
import sigil.tool.DiscoveryRequest

/**
 * Coverage for sigil bug #158 — `find_capability`'s BM25 ranker must
 * honor a tool's curated `keywords` over incidental description-prose
 * matches. The wire-log failure case: an agent asks to pin the
 * conversation's complexity tier, and the ranker returns `change_mode`
 * above `pin_complexity` purely because `change_mode`'s description
 * is long and happens to tokenize against the query.
 *
 * Fix: `keywords` is repeated [[sigil.tool.Tool.KeywordSearchBoost]]×
 * in the indexed `searchText`, so BM25's term-frequency contribution
 * from curated tokens dominates description prose. `ChangeModeTool`
 * also gains a tight `keywords` set tied to its actual semantics
 * (`mode` / `switch` / `posture` / `toolset`) so it doesn't accidentally
 * outrank adjacent intents.
 */
class FindCapabilityRankingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "find_capability ranker" should {

    "rank pin_complexity above change_mode for a tier-pinning query" in {
      val req = DiscoveryRequest(
        keywords = "complexity pin adjust medium level",
        chain = List(TestUser, TestAgent),
        mode = ConversationMode,
        callerSpaces = Set(sigil.GlobalSpace),
        conversationId = None
      )
      TestSigil.findTools(req).map { hits =>
        val names = hits.map(_.schema.name.value)
        val pinIdx = names.indexOf("pin_complexity")
        val changeIdx = names.indexOf("change_mode")
        withClue(s"discovery results: $names — pin_complexity at $pinIdx, change_mode at $changeIdx — ") {
          pinIdx should be >= 0
          if (changeIdx >= 0) {
            pinIdx should be < changeIdx
          } else succeed
        }
      }
    }

    "rank change_mode above pin_complexity for a mode-switching query" in {
      val req = DiscoveryRequest(
        keywords = "switch operating mode change toolset",
        chain = List(TestUser, TestAgent),
        mode = ConversationMode,
        callerSpaces = Set(sigil.GlobalSpace),
        conversationId = None
      )
      TestSigil.findTools(req).map { hits =>
        val names = hits.map(_.schema.name.value)
        val changeIdx = names.indexOf("change_mode")
        val pinIdx = names.indexOf("pin_complexity")
        withClue(s"discovery results: $names — change_mode at $changeIdx, pin_complexity at $pinIdx — ") {
          changeIdx should be >= 0
          if (pinIdx >= 0) {
            changeIdx should be < pinIdx
          } else succeed
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
