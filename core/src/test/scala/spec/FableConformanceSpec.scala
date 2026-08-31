package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{GenerationSettings, Instructions}
import sigil.provider.anthropic.AnthropicProvider
import sigil.signal.EventState
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

/**
 * Live conformance for the Claude 5 generation (Fable 5 / Mythos 5)
 * actually working as an agent model. These models reject BOTH a forced
 * `tool_choice` (sigil #387) AND deprecated sampling params (sigil #390 —
 * `temperature`), each with an HTTP 400; a normal agent turn sends both,
 * so without the provider self-heals the model is completely unusable.
 *
 * This test reproduces the exact Shopkeeper failure — a real agent turn
 * with `temperature` set and the default forced tool_choice — against the
 * live API, and asserts it settles successfully (both self-heals fire).
 *
 * Skipped cleanly when `ANTHROPIC_API_KEY` is not set; gated by
 * [[AnthropicLiveSupport]] so a rejected key cancels the suite without
 * per-test noise. Override the model id with `ANTHROPIC_FABLE_MODEL` if
 * the catalog id differs.
 */
class FableConformanceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] =
    Model.id(sys.env.getOrElse("ANTHROPIC_FABLE_MODEL", "anthropic/claude-fable-5"))
  // Register with Fable 5's REAL catalog `supported_parameters` — notably
  // OMITTING temperature/top_p — so this exercises the proactive #390 gate
  // (temperature is never sent) on top of the live API, not just the
  // cold-cache self-heal. tool_choice IS listed (the catalog can't express
  // the "no forcing" constraint), so the #387 tool_choice self-heal still
  // carries that case.
  TestSigil.cache.merge(List(TestSigil.testModel(modelId).copy(
    supportedParameters = Set(
      "include_reasoning",
      "max_completion_tokens",
      "max_tokens",
      "reasoning",
      "response_format",
      "stop",
      "structured_outputs",
      "tool_choice",
      "tools",
      "verbosity"
    )
  ))).sync()

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    AnthropicLiveSupport.runGatedForModel(this, testName, args, modelId.value) {
      super.run(testName, args)
    }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames,
      instructions = Instructions(),
      // The two things Fable/Mythos reject ride this turn: a sampling param
      // here, and the forced tool_choice the framework defaults to.
      generationSettings = GenerationSettings(temperature = Some(0.7), maxOutputTokens = Some(256))
    )

  "Claude Fable 5 conformance (sigil #387 + #390)" should {
    "complete a real agent turn despite default forced tool_choice + temperature" in {
      val convId = Conversation.id(s"fable-conformance-${rapid.Unique()}")
      val agent = makeAgent()
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      TestSigil.setProvider(AnthropicProvider.create(TestSigil, AnthropicLiveSupport.apiKey.getOrElse("")))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("Say hello in one short sentence.")),
          state = EventState.Complete
        ))
        _ <- TestSigil.awaitSettled(convId)
        evs <- TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))
      } yield {
        // The conformance signal: NO Failure bubble. A propagated 400
        // (forced tool_choice or deprecated temperature) would surface as a
        // Failure Message via the agent loop's handleError path.
        val failures = evs.collect { case m: Message if m.isFailure => m }
        withClue(s"failures=${failures.flatMap(_.content.collect { case t: ResponseContent.Text => t.text })}: ") {
          failures shouldBe empty
        }
        // And the agent actually replied.
        val replies = evs.collect {
          case m: Message if m.participantId == TestAgent && m.isSuccess && m.role == MessageRole.Standard => m
        }
        replies should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
