package bench

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{GenerationSettings, Instructions}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.tool.core.CoreTools
import spec.{TestAgent, TestSigil, TestTopicEntry, TestUser}

import scala.concurrent.duration.*

/**
 * Validator for [[AgentBenchHarness]]: drives a real multi-turn
 * conversation through the harness against the local llama.cpp
 * server, checking the resulting [[ConversationTrace]] has the
 * shape benchmark scorers will rely on (per-turn events, tool
 * invokes, final reply, final persisted Conversation).
 *
 * Runs only when `TestSigil.llamaCppHost` is reachable — same
 * convention as the per-provider conversation specs in core.
 */
class AgentBenchHarnessSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  override implicit protected val testTimeout: FiniteDuration = 3.minutes

  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProvider(LlamaCppProvider(TestSigil, TestSigil.llamaCppHost).singleton)

  private val modelId: Id[Model] = Model.id("qwen3.5-9b-q4_k_m")

  private def makeAgent() = DefaultAgentParticipant(
    id = TestAgent,
    modelId = modelId,
    toolNames = CoreTools.coreToolNames,
    instructions = Instructions(),
    generationSettings = GenerationSettings(maxOutputTokens = Some(4000), temperature = Some(0.0))
  )

  private val conversationFactory: Id[Conversation] => Conversation = convId =>
    Conversation(
      topics = List(TestTopicEntry),
      _id = convId,
      participants = List(makeAgent())
    )

  private val harness: AgentBenchHarness = AgentBenchHarness(TestSigil, TestUser)

  "AgentBenchHarness" should {
    "capture a single-turn conversation as a ConversationTrace with the expected shape" in {
      harness.runOneShot(conversationFactory, "Reply with the single word 'hi'.").map { trace =>
        trace.turns should have size 1
        val turn = trace.turns.head
        turn.events should not be empty
        // The agent fires `respond` to produce a reply; at minimum one
        // tool invocation should land in the turn window.
        turn.toolInvokes should not be empty
        turn.finalReply.isDefined shouldBe true
        turn.replyText should not be empty
        // Final persisted Conversation should still be addressable.
        trace.finalConversation._id shouldBe trace.conversationId
        succeed
      }
    }

    "carry context across turns and surface mode changes when the user asks for code" in {
      val turns = List(
        "My favorite color is blue. Acknowledge in one short sentence.",
        "Write me a Scala function that computes the factorial of n."
      )
      harness.runConversation(conversationFactory, turns).map { trace =>
        trace.turns should have size 2
        // Turn 2 should have triggered a change_mode → coding.
        trace.allModeChanges.map(_.mode.name) should contain("coding")
        trace.finalConversation.currentMode.name shouldBe "coding"
        // Both turns produced an agent reply.
        trace.turns.flatMap(_.finalReply.toList) should have size 2
        succeed
      }
    }
  }
}
