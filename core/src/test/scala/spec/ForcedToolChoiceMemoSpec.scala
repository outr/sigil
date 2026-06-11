package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderStreamException, ProviderType, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import scala.jdk.CollectionConverters.*

/**
 * Sigil #395 — the #387 forced-`tool_choice` self-heal is stateless per
 * call, so a model that rejects forced tool use (Fable 5 / Mythos 5) re-pays
 * the 400-then-downgrade round-trip on EVERY agent-loop iteration — many
 * times within a single tool-heavy turn. A process-wide memo records the
 * first observed rejection and demotes forced choices to [[ToolChoice.Auto]]
 * up front on every later call, collapsing the cost to one per model per
 * process.
 *
 * Pinned here:
 *   - The pure memo helpers (record / query).
 *   - End-to-end: after a first turn trips the memo, a second turn's calls
 *     for that model go out as `Auto` from the start — no forced choice is
 *     ever sent again, so the rejection is never re-discovered.
 */
class ForcedToolChoiceMemoSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "forced-tool-choice-memo")
  TestSigil.testModel(modelId)

  private def rejection(message: String): ProviderStreamException =
    new ProviderStreamException(
      providerKey = "anthropic", code = 0, typ = "invalid_request_error",
      message_ = message, status = Some(400)
    )

  /** Fake provider that records every call's (modelId, toolChoice). It 400s
    * on ANY forced choice and emits a clean `respond` on `Auto`. */
  private final class ForcedChoiceRejecter extends Provider {
    val calls: java.util.concurrent.ConcurrentLinkedQueue[(String, ToolChoice)] =
      new java.util.concurrent.ConcurrentLinkedQueue[(String, ToolChoice)]()
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.add(input.model._id.value -> input.toolChoice)
      if (input.toolChoice.isForced)
        Stream.force(Task.error(rejection(
          "tool_choice forces tool use is not compatible with this model."
        )))
      else {
        val cid = CallId(s"respond-${calls.size()}")
        Stream.emits(List[ProviderEvent](
          ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
          ProviderEvent.ToolCallComplete(cid,
            RespondInput(topicLabel = "T", topicSummary = "s", content = "ok", endsTurn = true)),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings())

  private def turn(convId: Id[Conversation], text: String): Task[Unit] =
    for {
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text(text)),
             state          = EventState.Complete))
      _ <- TestSigil.awaitSettled(convId)
    } yield ()

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  "Provider forced-tool_choice memo (sigil #395)" should {

    "record and query a rejection process-wide" in Task {
      val memoOnly = Model.id("test", "memo-only-395")
      Provider.rejectsForcedToolChoice(memoOnly) shouldBe false
      Provider.recordForcedToolChoiceRejection(memoOnly)
      Provider.recordForcedToolChoiceRejection(memoOnly) // idempotent
      Provider.rejectsForcedToolChoice(memoOnly) shouldBe true
      Provider.rejectsForcedToolChoice(Model.id("test", "never-rejected-395")) shouldBe false
      succeed
    }

    "stop sending a forced tool_choice after the first rejection trips the memo" in {
      val provider = new ForcedChoiceRejecter
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"memo-${rapid.Unique()}")
      val conv   = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)

      // The model must NOT be pre-memoed for this to be a real test.
      Provider.rejectsForcedToolChoice(modelId) shouldBe false

      for {
        _        <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _        <- turn(convId, "First request.")
        boundary  = provider.calls.size()
        _        <- turn(convId, "Second request.")
        evs      <- eventsFor(convId)
      } yield {
        val all   = provider.calls.asScala.toList
        val turn1 = all.take(boundary).collect { case (m, tc) if m == modelId.value => tc }
        val turn2 = all.drop(boundary).collect { case (m, tc) if m == modelId.value => tc }

        // Turn 1 discovered the rejection: the first call went out forced,
        // then a downgraded Auto call followed — and the memo is now tripped.
        turn1.headOption.exists(_.isForced) shouldBe true
        turn1.exists(!_.isForced) shouldBe true
        Provider.rejectsForcedToolChoice(modelId) shouldBe true

        // Turn 2 is the payoff: every call for this model went out NON-forced
        // from the start. Pre-fix the first turn-2 call would again be forced
        // (then rejected + retried) — here the wasted round-trip never happens.
        turn2 should not be empty
        turn2.foreach(_.isForced shouldBe false)

        // Both turns produced a real reply; no Failure bubbles.
        evs.collect { case m: Message if m.participantId == TestAgent && m.isSuccess && m.role == MessageRole.Standard => m
          } should not be empty
        evs.collect { case m: Message if m.isFailure => m } shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
