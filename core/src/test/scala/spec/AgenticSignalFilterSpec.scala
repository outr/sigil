package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.compression.extract.{AgenticSignalFilter, DefaultHighSignalFilter, ExtractionTurn, HighSignalFilter}
import sigil.tool.ToolName

/**
 * Coverage for [[AgenticSignalFilter]] — the coding / agentic-corpus
 * extraction gate — and the [[HighSignalFilter.any]] combinator.
 */
class AgenticSignalFilterSpec extends AnyWordSpec with Matchers {
  private val f = AgenticSignalFilter

  "AgenticSignalFilter" should {
    "pass a turn that settled a tool mutation regardless of text" in {
      val turn = ExtractionTurn(
        userMessage = "ok",
        agentResponse = "Done.",
        settledMutations = List(ToolName("write_file"))
      )
      f.isHighSignal(turn) should be(true)
    }

    "pass decision language" in {
      f.isHighSignal("We decided to use RocksDB instead of Postgres for the default store") should be(true)
      f.isHighSignal("I chose the streaming parser, going with jsoniter here") should be(true)
    }

    "pass constraint and convention language" in {
      f.isHighSignal("Never use blocking IO inside the signal pipeline — that's the convention here") should be(true)
      f.isHighSignal("The API rejects requests without a tenant header") should be(true)
    }

    "pass error-name mentions" in {
      f.isHighSignal("The build fails with a NullPointerException in DbToolFinder") should be(true)
      f.isHighSignal("Getting TypeError when the payload is empty") should be(true)
    }

    "pass version pins" in {
      f.isHighSignal("Pin lightdb to 4.31.1 until the index regression is fixed") should be(true)
      f.isHighSignal("This only works on v2 of the endpoint") should be(true)
    }

    "pass decision language appearing only in the agent response" in {
      val turn = ExtractionTurn(
        userMessage = "sounds good",
        agentResponse = "I settled on the recursive descent approach because the grammar is small."
      )
      f.isHighSignal(turn) should be(true)
    }

    "pass an explicit user correction after an agent action" in {
      val turn = ExtractionTurn(
        userMessage = "No, that's wrong — the config lives under conf/, put it back",
        agentResponse = "I moved the config file to src/main/resources."
      )
      f.isHighSignal(turn) should be(true)
    }

    "not treat a correction-shaped message as a correction when the turn has no agent action" in {
      val turn = ExtractionTurn(userMessage = "no thanks, maybe later", agentResponse = "")
      f.isHighSignal(turn) should be(false)
    }

    "reject chit-chat" in {
      f.isHighSignal("Hey, how's it going today?") should be(false)
      f.isHighSignal("Thanks, that looks great!") should be(false)
      f.isHighSignal("Can you tell me more about how that works overall?") should be(false)
    }

    "reject a chit-chat turn with no mutations" in {
      f.isHighSignal(ExtractionTurn("looks good to me", "Glad it helps!")) should be(false)
    }

    "reject null and blank input" in {
      f.isHighSignal(null: String) should be(false)
      f.isHighSignal("") should be(false)
    }
  }

  "HighSignalFilter.any" should {
    val combined = HighSignalFilter.any(DefaultHighSignalFilter, AgenticSignalFilter)

    "pass messages either member passes" in {
      // Default's personal-assistant idiom (rejected by Agentic).
      combined.isHighSignal("I bought a new house in Brooklyn last Tuesday for a great price.") should be(true)
      // Agentic's decision idiom (rejected by Default — no personal markers, short).
      combined.isHighSignal("We decided to use tabs") should be(true)
    }

    "pass turns either member passes" in {
      combined.isHighSignal(ExtractionTurn("ok", "done", settledMutations = List(ToolName("bash")))) should be(true)
    }

    "reject what every member rejects" in {
      combined.isHighSignal("Hey, how's it going today?") should be(false)
      combined.isHighSignal(ExtractionTurn("thanks!", "You're welcome.")) should be(false)
    }
  }
}
