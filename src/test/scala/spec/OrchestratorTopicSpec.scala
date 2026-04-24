package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ConversationView, Conversation, Topic, TopicEntry, TopicShiftResult, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, TopicChange, TopicChangeKind}
import sigil.orchestrator.Orchestrator
import sigil.provider.{CallId, ConversationRequest, GenerationSettings, Instructions, Mode, ConversationMode, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.signal.Signal
import sigil.tool.core.RespondTool
import sigil.tool.consult.TopicClassifierInput
import sigil.tool.model.RespondInput
import spice.http.HttpRequest

/**
 * Verifies the orchestrator's two-step topic-resolution flow end-to-end
 * using scripted providers (no real LLM).
 *
 * Coverage matrix (label matches short-circuit the classifier; all others
 * go through it):
 *   - Exact match with current label       → NoChange (no classifier call)
 *   - Exact match with prior label         → Switch (return), no classifier call
 *   - Classifier says NoChange             → no event
 *   - Classifier says Refine               → Rename current (label + summary update)
 *   - Classifier says New                  → create new Topic + Switch (push)
 *   - Classifier says Return:<prior>       → Switch to that prior (truncate)
 */
class OrchestratorTopicSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /**
   * Scripted provider — handles both call shapes via tool inspection on
   * the uniform [[ProviderCall]]:
   *
   *   - If the call's tool roster contains `classify_topic_shift`, this
   *     is the framework's classifier path. Emit a scripted tool call
   *     carrying [[TopicClassifierInput]] for the configured kind, or an
   *     empty stream when `classifierKind` is `None` (simulates a
   *     provider failure / model refusal).
   *   - Otherwise this is a primary respond turn. Emit a scripted
   *     `respond` tool call carrying the supplied [[RespondInput]].
   */
  private class StubProvider(input: RespondInput, classifierKind: Option[String]) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("StubProvider: no wire rendering"))
    override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
      val isClassifier = input.tools.exists(_.schema.name.value == "classify_topic_shift")
      if (isClassifier) classifierKind match {
        case Some(kind) =>
          val callId = CallId("stub-classify")
          Stream.emits(List(
            ProviderEvent.ToolCallStart(callId, "classify_topic_shift"),
            ProviderEvent.ToolCallComplete(callId, TopicClassifierInput(kind)),
            ProviderEvent.Done(StopReason.ToolCall)
          ))
        case None =>
          Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
      } else {
        val callId = CallId("stub-call")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
          ProviderEvent.ContentBlockStart(callId, "Text", None),
          ProviderEvent.ContentBlockDelta(callId, "scripted content"),
          ProviderEvent.ToolCallComplete(callId, this.input),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
      }
    }
  }

  /** Upsert a conversation + seeded Topic stack, then run
    * `Orchestrator.process` with a StubProvider emitting a `respond` carrying
    * `respondInput`. If `classifierKind` is supplied, the stubbed consult
    * call returns that kind to drive the classifier path. */
  private def runScenario(currentLabel: String,
                          currentSummary: String,
                          priors: List[TopicEntry],
                          respondInput: RespondInput,
                          classifierKind: Option[String],
                          suffix: String): Task[(List[Signal], Topic, Id[Conversation])] = {
    val convId = Conversation.id(s"topic-orch-$suffix-${rapid.Unique()}")
    val current = Topic(
      conversationId = convId,
      label = currentLabel,
      summary = currentSummary,
      createdBy = TestUser
    )
    val currentEntry = TopicEntry(current._id, current.label, current.summary)
    val conv = Conversation(topics = priors :+ currentEntry, _id = convId)
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    val stubProvider = new StubProvider(respondInput, classifierKind)
    // Register stub provider so Sigil.providerFor returns it for classifier calls.
    TestSigil.setProvider(Task.pure(stubProvider))
    val request = ConversationRequest(
      conversationId = convId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(view),
      currentMode = ConversationMode,
      currentTopic = currentEntry,
      previousTopics = priors,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain = List(TestUser, TestAgent)
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(current)))
      signals <- Orchestrator.process(TestSigil, stubProvider, request).toList
    } yield (signals, current, convId)
  }

  "Orchestrator topic resolution — label-equality shortcuts" should {

    "emit NO TopicChange when the proposed label equals the current label (no classifier call)" in {
      val input = RespondInput(
        content = "▶Text\nscripted",
        topicLabel = "Existing Thread",
        topicSummary = "Same thread."
      )
      runScenario("Existing Thread", "Current thread summary", Nil, input, classifierKind = None,
                  suffix = "same-label").map { case (signals, _, _) =>
        signals.collect { case tc: TopicChange => tc } shouldBe empty
        signals.collect { case m: Message => m } should have size 1
      }
    }

    "emit a TopicChange(Switch) directly when the proposed label equals a prior label (no classifier call)" in {
      val priorId = Topic.id("prior-py")
      val priorEntry = TopicEntry(priorId, "Python GIL", "Python Global Interpreter Lock.")
      val input = RespondInput(
        content = "▶Text\nback to GIL",
        topicLabel = "Python GIL",
        topicSummary = "Returning to the GIL topic."
      )
      runScenario("Cooking", "Culinary discussion.", List(priorEntry), input, classifierKind = None,
                  suffix = "exact-return").map { case (signals, current, _) =>
        val topicChanges = signals.collect { case tc: TopicChange => tc }
        topicChanges should have size 1
        val tc = topicChanges.head
        tc.kind shouldBe a[TopicChangeKind.Switch]
        tc.kind.asInstanceOf[TopicChangeKind.Switch].previousTopicId shouldBe current._id
        tc.topicId shouldBe priorId
        tc.newLabel shouldBe "Python GIL"
      }
    }
  }

  "Orchestrator topic resolution — classifier outcomes" should {

    "emit NO TopicChange when the classifier returns NoChange" in {
      val input = RespondInput(
        content = "▶Text\nscripted",
        topicLabel = "Python GIL and I/O",
        topicSummary = "Effect of GIL on I/O-bound Python code."
      )
      runScenario("Python GIL", "Python's Global Interpreter Lock.", Nil, input,
                  classifierKind = Some("NoChange"), suffix = "cls-nochange").map { case (signals, _, _) =>
        signals.collect { case tc: TopicChange => tc } shouldBe empty
      }
    }

    "emit a TopicChange(Rename) with label + summary updated when the classifier returns Refine" in {
      val input = RespondInput(
        content = "▶Text\nscripted",
        topicLabel = "Python GIL",
        topicSummary = "Python's Global Interpreter Lock and threading."
      )
      runScenario("Python Programming", "General Python.", Nil, input,
                  classifierKind = Some("Refine"), suffix = "cls-refine").flatMap {
        case (signals, current, _) =>
          val topicChanges = signals.collect { case tc: TopicChange => tc }
          topicChanges should have size 1
          val tc = topicChanges.head
          tc.kind shouldBe TopicChangeKind.Rename("Python Programming")
          tc.newLabel shouldBe "Python GIL"
          tc.topicId shouldBe current._id

          TestSigil.withDB(_.topics.transaction(_.get(current._id))).map { loaded =>
            loaded.map(_.label) shouldBe Some("Python GIL")
            loaded.map(_.summary) shouldBe Some("Python's Global Interpreter Lock and threading.")
          }
      }
    }

    "emit a TopicChange(Switch) and persist a new Topic when the classifier returns New" in {
      val input = RespondInput(
        content = "▶Text\nscripted",
        topicLabel = "TypeScript Generics",
        topicSummary = "TypeScript's generic type parameterization."
      )
      runScenario("Roman Empire", "Roman history.", Nil, input,
                  classifierKind = Some("New"), suffix = "cls-new").flatMap {
        case (signals, current, convId) =>
          val topicChanges = signals.collect { case tc: TopicChange => tc }
          topicChanges should have size 1
          val tc = topicChanges.head
          tc.kind shouldBe a[TopicChangeKind.Switch]
          tc.kind.asInstanceOf[TopicChangeKind.Switch].previousTopicId shouldBe current._id
          tc.topicId should not be current._id
          tc.newLabel shouldBe "TypeScript Generics"

          TestSigil.withDB(_.topics.transaction(_.get(tc.topicId))).map { loaded =>
            loaded.map(_.label) shouldBe Some("TypeScript Generics")
            loaded.map(_.summary) shouldBe Some("TypeScript's generic type parameterization.")
            loaded.map(_.conversationId) shouldBe Some(convId)
          }
      }
    }

    "emit a TopicChange(Switch) to a prior when the classifier returns that prior's label" in {
      val priorId = Topic.id("prior-returning")
      val priorEntry = TopicEntry(priorId, "Python GIL", "GIL topic from earlier.")
      // Proposed label is different from current AND different from the prior's exact label,
      // but classifier decides it's semantically the same as the prior.
      val input = RespondInput(
        content = "▶Text\nscripted",
        topicLabel = "GIL and NumPy",
        topicSummary = "GIL implications for NumPy."
      )
      runScenario("Cooking", "Culinary topic.", List(priorEntry), input,
                  classifierKind = Some("Python GIL"), suffix = "cls-return").map {
        case (signals, current, _) =>
          val topicChanges = signals.collect { case tc: TopicChange => tc }
          topicChanges should have size 1
          val tc = topicChanges.head
          tc.kind shouldBe a[TopicChangeKind.Switch]
          tc.kind.asInstanceOf[TopicChangeKind.Switch].previousTopicId shouldBe current._id
          tc.topicId shouldBe priorId
          tc.newLabel shouldBe "Python GIL"
      }
    }

    "emit NO TopicChange when the classifier call fails (fallback)" in {
      val input = RespondInput(
        content = "▶Text\nscripted",
        topicLabel = "Unrelated Label",
        topicSummary = "Something."
      )
      // classifierKind = None → stub returns no tool call → classify falls back to NoChange
      runScenario("Current", "Current summary.", Nil, input,
                  classifierKind = None, suffix = "cls-fail").map { case (signals, _, _) =>
        signals.collect { case tc: TopicChange => tc } shouldBe empty
      }
    }
  }

  "Message topicId tagging" should {
    "tag the emitted Message with the current topicId (shift takes effect next message)" in {
      val input = RespondInput(
        content = "▶Text\nscripted",
        topicLabel = "New Subject",
        topicSummary = "A new subject."
      )
      runScenario("Original", "Original summary.", Nil, input,
                  classifierKind = Some("New"), suffix = "msg-topicid").map {
        case (signals, current, _) =>
          val messages = signals.collect { case m: Message => m }
          messages should have size 1
          messages.head.topicId shouldBe current._id
      }
    }
  }

  "Multi-turn topic switching via publish pipeline" should {
    "update Conversation.topics after a TopicChange(Switch) settles, so the next turn reads the new stack" in {
      val convId = Conversation.id(s"topic-multiturn-${rapid.Unique()}")
      val firstTopic = Topic(conversationId = convId, label = "First", summary = "First subject.", createdBy = TestUser)
      val secondTopic = Topic(conversationId = convId, label = "Second", summary = "Second subject.", createdBy = TestUser)
      val firstEntry = TopicEntry(firstTopic._id, firstTopic.label, firstTopic.summary)
      val conv = Conversation(topics = List(firstEntry), _id = convId)
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
      } yield {
        convAfterSwitch.get.topics.map(_.id) shouldBe List(firstTopic._id, secondTopic._id)
        convAfterSwitch.get.currentTopicId shouldBe secondTopic._id
      }
    }
  }
}
