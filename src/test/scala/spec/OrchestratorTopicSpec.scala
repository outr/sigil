package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ConversationView, Conversation, Topic, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, TopicChange, TopicChangeKind}
import sigil.orchestrator.Orchestrator
import sigil.provider.{CallId, GenerationSettings, Instructions, Mode, Provider, ProviderEvent, ProviderRequest, ProviderType, StopReason}
import sigil.signal.{Signal, StateDelta}
import sigil.tool.core.RespondTool
import sigil.tool.model.{RespondInput, TopicChangeType}
import spice.http.HttpRequest

/**
 * Verifies the orchestrator's categorical topic-resolution logic end-to-end:
 * a scripted [[StubProvider]] emits the ProviderEvent sequence for a
 * streamed `respond` call carrying a specific `topic` + `topicChangeType`,
 * and we assert on the resulting [[Signal]] stream — specifically whether
 * (and how) a [[TopicChange]] was emitted.
 *
 * Coverage matrix:
 *   - `NoChange`              + any label        → NO TopicChange
 *   - `Change` + new label                       → Switch + persist new Topic
 *   - `Change` + label matching existing Topic   → Switch to existing Topic
 *   - `Change` + label == current label          → NO TopicChange (inconsistent input; label wins)
 *   - `Update` + new label  (unlocked)           → Rename current Topic
 *   - `Update` + new label  (locked)             → NO TopicChange
 *   - `Update` + label == current label          → NO TopicChange
 *
 * The multi-turn invariant (second message after a switch carries the
 * new topicId) is covered in [[ConversationViewSpec]] because it's
 * driven by `Sigil.updateConversationProjection` on the TopicChange
 * settle rather than by the orchestrator itself.
 */
class OrchestratorTopicSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /**
   * Scripted provider — emits the ToolCallStart / ContentBlockStart /
   * ContentBlockDelta / ToolCallComplete sequence for a single `respond`
   * call carrying the supplied input. Used to drive `Orchestrator.process`
   * deterministically without talking to an LLM.
   */
  private class StubProvider(input: RespondInput) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override def requestConverter(request: ProviderRequest): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("StubProvider: no wire rendering"))
    override def apply(request: ProviderRequest): Stream[ProviderEvent] = {
      val callId = CallId("stub-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ContentBlockStart(callId, "Text", None),
        ProviderEvent.ContentBlockDelta(callId, "scripted content"),
        ProviderEvent.ToolCallComplete(callId, input),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  /**
   * Upsert a conversation + seeded Topic, then run `Orchestrator.process`
   * with a StubProvider emitting a `respond` carrying `input`. Returns
   * (signals, initialTopic, conversationId) for assertions.
   */
  private def runWithTopic(label: String,
                           locked: Boolean,
                           input: RespondInput,
                           suffix: String): Task[(List[Signal], Topic, Id[Conversation])] = {
    val convId = Conversation.id(s"topic-orchestrator-$suffix-${rapid.Unique()}")
    val topic = Topic(
      conversationId = convId,
      label = label,
      labelLocked = locked,
      createdBy = TestUser
    )
    val conv = Conversation(currentTopicId = topic._id, _id = convId)
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    val request = ProviderRequest(
      conversationId = convId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(view),
      currentMode = Mode.Conversation,
      currentTopicId = topic._id,
      currentTopicLabel = topic.label,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain = List(TestUser, TestAgent)
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(topic)))
      signals <- Orchestrator.process(TestSigil, new StubProvider(input), request).toList
    } yield (signals, topic, convId)
  }

  "Orchestrator topic resolution on a respond" should {

    "emit NO TopicChange when topicChangeType = NoChange (even if the submitted topic differs)" in {
      // NoChange is authoritative — the framework ignores any diff in the
      // topic label and emits nothing.
      val input = RespondInput(
        content = "▶Text\nscripted content",
        topic = "Something Else Entirely",
        topicChangeType = TopicChangeType.NoChange
      )
      runWithTopic("Current Thread", locked = false, input, "no-change-wins").map { case (signals, _, _) =>
        signals.collect { case tc: TopicChange => tc } shouldBe empty
        signals.collect { case m: Message => m } should have size 1
      }
    }

    "emit NO TopicChange when the submitted label matches the current label" in {
      val input = RespondInput(
        content = "▶Text\nscripted content",
        topic = "Existing Thread",
        topicChangeType = TopicChangeType.NoChange
      )
      runWithTopic("Existing Thread", locked = false, input, "same-label").map { case (signals, _, _) =>
        signals.collect { case tc: TopicChange => tc } shouldBe empty
      }
    }

    "emit a TopicChange(Switch) and persist a new Topic when topicChangeType = Change and label is new" in {
      val input = RespondInput(
        content = "▶Text\nscripted content",
        topic = "Brand New Subject",
        topicChangeType = TopicChangeType.Change
      )
      runWithTopic("Initial", locked = false, input, "switch-new").flatMap {
        case (signals, initial, convId) =>
          val topicChanges = signals.collect { case tc: TopicChange => tc }
          topicChanges should have size 1
          val tc = topicChanges.head
          tc.kind shouldBe a[TopicChangeKind.Switch]
          tc.kind.asInstanceOf[TopicChangeKind.Switch].previousTopicId shouldBe initial._id
          tc.newLabel shouldBe "Brand New Subject"
          tc.topicId should not be initial._id

          // Settling StateDelta follows the TopicChange pulse.
          val settle = signals.collectFirst {
            case sd: StateDelta if sd.target == tc._id => sd
          }
          settle should not be empty

          // The new Topic record is persisted with the new label.
          TestSigil.withDB(_.topics.transaction(_.get(tc.topicId))).map { loaded =>
            loaded.map(_.label) shouldBe Some("Brand New Subject")
            loaded.map(_.conversationId) shouldBe Some(convId)
          }
      }
    }

    "emit a TopicChange(Switch) that reuses an existing Topic in the conversation when the label already exists" in {
      val input = RespondInput(
        content = "▶Text\nscripted content",
        topic = "Prior Thread",
        topicChangeType = TopicChangeType.Change
      )
      // Start with topic "Current", and pre-populate the conversation with
      // another topic "Prior Thread" that the switch should reuse.
      val convId = Conversation.id(s"topic-orchestrator-switch-reuse-${rapid.Unique()}")
      val current = Topic(conversationId = convId, label = "Current", createdBy = TestUser)
      val prior   = Topic(conversationId = convId, label = "Prior Thread", createdBy = TestUser)
      val conv = Conversation(currentTopicId = current._id, _id = convId)
      val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
      val request = ProviderRequest(
        conversationId = convId,
        modelId = modelId,
        instructions = Instructions(),
        turnInput = TurnInput(view),
        currentMode = Mode.Conversation,
        currentTopicId = current._id,
        currentTopicLabel = current.label,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        chain = List(TestUser, TestAgent)
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.withDB(_.topics.transaction(_.upsert(current)))
        _ <- TestSigil.withDB(_.topics.transaction(_.upsert(prior)))
        signals <- Orchestrator.process(TestSigil, new StubProvider(input), request).toList
      } yield {
        val topicChanges = signals.collect { case tc: TopicChange => tc }
        topicChanges should have size 1
        topicChanges.head.topicId shouldBe prior._id
        topicChanges.head.newLabel shouldBe "Prior Thread"
      }
    }

    "emit a TopicChange(Rename) and mutate the Topic in-place when topicChangeType = Update on an unlocked topic" in {
      val input = RespondInput(
        content = "▶Text\nscripted content",
        topic = "Refined Label",
        topicChangeType = TopicChangeType.Update
      )
      runWithTopic("Initial Label", locked = false, input, "rename-unlocked").flatMap {
        case (signals, initial, _) =>
          val topicChanges = signals.collect { case tc: TopicChange => tc }
          topicChanges should have size 1
          val tc = topicChanges.head
          tc.kind shouldBe TopicChangeKind.Rename("Initial Label")
          tc.newLabel shouldBe "Refined Label"
          tc.topicId shouldBe initial._id

          TestSigil.withDB(_.topics.transaction(_.get(initial._id))).map { loaded =>
            loaded.map(_.label) shouldBe Some("Refined Label")
          }
      }
    }

    "emit NO TopicChange when the Topic is labelLocked and topicChangeType = Update" in {
      val input = RespondInput(
        content = "▶Text\nscripted content",
        topic = "Attempted Rename",
        topicChangeType = TopicChangeType.Update
      )
      runWithTopic("Pinned", locked = true, input, "rename-locked").flatMap {
        case (signals, initial, _) =>
          signals.collect { case tc: TopicChange => tc } shouldBe empty
          TestSigil.withDB(_.topics.transaction(_.get(initial._id))).map { loaded =>
            loaded.map(_.label) shouldBe Some("Pinned")
          }
      }
    }

    "emit NO TopicChange when topicChangeType = Change but the label matches the current label (inconsistent input)" in {
      // LLM declared a Change but handed back the same label — label wins,
      // no event fires and no duplicate Topic gets created.
      val input = RespondInput(
        content = "▶Text\nscripted content",
        topic = "Stable Topic",
        topicChangeType = TopicChangeType.Change
      )
      runWithTopic("Stable Topic", locked = false, input, "change-same-label").map {
        case (signals, _, _) =>
          signals.collect { case tc: TopicChange => tc } shouldBe empty
      }
    }

    "tag the emitted Message with the original topicId (Switch takes effect on the NEXT message)" in {
      val input = RespondInput(
        content = "▶Text\nscripted content",
        topic = "New Subject",
        topicChangeType = TopicChangeType.Change
      )
      runWithTopic("Original Subject", locked = false, input, "message-topic-tag").map {
        case (signals, initial, _) =>
          // The Message emitted during this turn was created at
          // ContentBlockDelta time — BEFORE the ToolCallComplete revealed
          // the topic shift. So its topicId is the topic that was active
          // when the stream started.
          val messages = signals.collect { case m: Message => m }
          messages should have size 1
          messages.head.topicId shouldBe initial._id
      }
    }
  }

  "Multi-turn topic switching" should {
    "route a subsequent publish's Message to the new topic after a TopicChange(Switch) settles" in {
      // Simulate two turns:
      //   Turn 1 — publish a TopicChange(Switch) complete event and its settle; projection moves currentTopicId.
      //   Turn 2 — publish a Message; verify it lands on the new topic because the Conversation record says so.
      val convId = Conversation.id(s"topic-multiturn-${rapid.Unique()}")
      val firstTopic = Topic(conversationId = convId, label = "First", createdBy = TestUser)
      val secondTopic = Topic(conversationId = convId, label = "Second", createdBy = TestUser)
      val conv = Conversation(currentTopicId = firstTopic._id, _id = convId)
      val tc = TopicChange(
        kind = TopicChangeKind.Switch(previousTopicId = firstTopic._id),
        newLabel = "Second",
        participantId = TestAgent,
        conversationId = convId,
        topicId = secondTopic._id,
        state = sigil.signal.EventState.Complete
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.withDB(_.topics.transaction(_.upsert(firstTopic)))
        _ <- TestSigil.withDB(_.topics.transaction(_.upsert(secondTopic)))
        _ <- TestSigil.publish(tc)
        convAfterSwitch <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
        // The caller of publish (e.g. the agent building its next request)
        // reads the updated currentTopicId and tags its next Message with it.
        nextMsg = Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = convAfterSwitch.get.currentTopicId,
          content = Vector(sigil.tool.model.ResponseContent.Text("continuing on new topic")),
          state = sigil.signal.EventState.Complete
        )
        _ <- TestSigil.publish(nextMsg)
      } yield {
        convAfterSwitch.map(_.currentTopicId) shouldBe Some(secondTopic._id)
        nextMsg.topicId shouldBe secondTopic._id
      }
    }
  }
}
