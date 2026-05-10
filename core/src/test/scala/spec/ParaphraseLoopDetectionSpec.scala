package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, ContextKey}
import sigil.conversation.compression.ParaphraseLoopDetector
import sigil.event.Event
import sigil.participant.ParticipantId

/**
 * Coverage for [[ParaphraseLoopDetector]] — the framework primitive
 * that surfaces "respond drafts without action" as a meta-observation
 * the model can read on its next turn.
 *
 * Scenarios mirror the bug report:
 *
 *   1. 4+ near-duplicate respond drafts in a row → detector fires
 *      with the first-occurrence observation copy.
 *   2. Substantively different responds → no detection.
 *   3. The exact 6-draft scenario from the live wire log triggers.
 *   4. After the agent breaks the pattern (invokes a real tool),
 *      the detector clears.
 *   5. A fifth consecutive draft escalates the observation copy.
 *
 * The detector is pure logic — no Sigil instance, no DB. Specs run
 * synchronously.
 */
class ParaphraseLoopDetectionSpec extends AnyWordSpec with Matchers {

  private val agent: ParticipantId = TestAgent
  private val user: ParticipantId  = TestUser

  private def text(content: String, who: ParticipantId): ContextFrame.Text =
    ContextFrame.Text(
      content       = content,
      participantId = who,
      sourceEventId = Id[Event](rapid.Unique())
    )

  private def toolCall(name: String): ContextFrame.ToolCall =
    ContextFrame.ToolCall(
      toolName      = sigil.tool.ToolName(name),
      argsJson      = "{}",
      callId        = Id[Event](rapid.Unique()),
      participantId = agent,
      sourceEventId = Id[Event](rapid.Unique())
    )

  private val detector = ParaphraseLoopDetector()

  "ParaphraseLoopDetector" should {

    "fire on 4 near-duplicate respond drafts (no tool execution between them)" in {
      val frames = Vector[ContextFrame](
        text("Evaluate the password reset functionality", user),
        text("Found many password-related files. Let me pull up the full list.", agent),
        text("Found a number of password-related files. Let me pull up the full list.", agent),
        text("Found password-related files. Let me pull up the full list.", agent),
        text("Found many password files. Let me pull up the full list and key source files.", agent)
      )
      val pattern = detector.detect(frames, agent)
      pattern shouldBe defined
      pattern.get.count shouldBe 4
      pattern.get.escalated shouldBe false
      pattern.get.samples should have size 3
    }

    "render an observation that names the pattern and lists samples" in {
      val frames = Vector[ContextFrame](
        text("evaluate", user),
        text("Found many password files. Let me pull up the full list.", agent),
        text("Found a number of password files. Let me pull up the full list.", agent),
        text("Found password files. Let me pull up the full list.", agent),
        text("Found many password files. Let me pull up the full list.", agent)
      )
      val rendered = detector.detect(frames, agent).get.render()
      rendered should include ("FRAMEWORK OBSERVATION")
      rendered should include ("paraphrase")
      rendered should include ("non-respond tool")
      rendered should include ("Let me pull up")
    }

    "NOT fire on substantively different responds" in {
      val frames = Vector[ContextFrame](
        text("evaluate password reset", user),
        text("Found 12 password files matching glob.", agent),
        text("Reading AdminUserResetPasswordService.scala now.", agent),
        text("The reset flow uses a 6-digit token, expires in 1 hour, single-use enforced via DB column.", agent),
        text("Token is also single-use via UPDATE ... WHERE used = false.", agent)
      )
      detector.detect(frames, agent) shouldBe None
    }

    "repro: the exact 6-draft wire-log scenario triggers" in {
      val drafts = List(
        "Found a number of password-related files. Let me pull up the full list.",
        "Found many password-related files. Let me pull up the full list.",
        "Found password-related files across the project. Let me pull up the full list and key source files.",
        "Found many password-related files. Let me pull up the full list and key source files.",
        "Found a lot of password-related files. Let me pull up the full list.",
        "Found a number of password-related files. Let me pull up the full list and key source files."
      )
      val frames = Vector(text("Evaluate the password reset functionality", user)) ++
        drafts.map(t => text(t, agent)).toVector
      val pattern = detector.detect(frames, agent)
      pattern shouldBe defined
      pattern.get.count shouldBe 6
      pattern.get.escalated shouldBe true   // 6 ≥ escalationThreshold(5)
      pattern.get.render() should include ("SECOND OCCURRENCE")
    }

    "clear when the agent breaks the pattern with a real tool call" in {
      val frames = Vector[ContextFrame](
        text("evaluate", user),
        text("Found many files. Let me pull up the full list.", agent),
        text("Found many files. Let me pull up the full list.", agent),
        text("Found many files. Let me pull up the full list.", agent),
        text("Found many files. Let me pull up the full list.", agent),
        toolCall("read_file")  // boundary — pattern cleared
      )
      detector.detect(frames, agent) shouldBe None
    }

    "escalate when 5 consecutive drafts have stacked up" in {
      val drafts = (1 to 5).map(i => text(s"Found $i password files. Let me pull up the full list.", agent))
      val frames = Vector[ContextFrame](text("evaluate", user)) ++ drafts.toVector
      val pattern = detector.detect(frames, agent)
      pattern shouldBe defined
      pattern.get.escalated shouldBe true
      pattern.get.render() should include ("SECOND OCCURRENCE")
    }

    "respect the agentId filter — drafts from a different agent don't count" in {
      val otherAgent: ParticipantId = new ParticipantId { val value: String = "other-agent" }
      val frames = Vector[ContextFrame](
        text("evaluate", user),
        text("Let me pull up the full list.", otherAgent),
        text("Let me pull up the full list.", otherAgent),
        text("Let me pull up the full list.", otherAgent),
        text("Let me pull up the full list.", otherAgent)
      )
      // Our agent has zero responds → no detection.
      detector.detect(frames, agent) shouldBe None
      // The other agent has 4 matching drafts → detection fires for it.
      detector.detect(frames, otherAgent) shouldBe defined
    }
  }

  "Curator integration" should {

    "expose the observation under the documented context key" in {
      ParaphraseLoopDetector.ContextKeyValue shouldBe "_paraphraseObservation"
      ContextKey(ParaphraseLoopDetector.ContextKeyValue).value shouldBe "_paraphraseObservation"
    }
  }
}
