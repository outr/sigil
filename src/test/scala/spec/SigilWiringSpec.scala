package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, Topic}
import sigil.db.Model
import sigil.event.{Message, ModeChange, TopicChange, TopicChangeKind, ToolInvoke}
import sigil.participant.{DefaultAgentParticipant, Participant}
import sigil.provider.{GenerationSettings, Instructions, Mode, TokenUsage}
import sigil.signal.{ContentDelta, ContentKind, EventState, MessageDelta, Signal, ToolDelta}
import sigil.tool.{ToolInput, ToolName}
import sigil.tool.core.CoreTools
import sigil.tool.model.{ChangeModeInput, RespondInput, ResponseContent, TopicChangeType}

/**
 * Verifies that [[TestSigil.instance]] registers core Signal and ToolInput
 * subtypes correctly — i.e., that polymorphic round-trip through the wire
 * format actually works for everything sigil ships and for app-supplied
 * Input types surfaced through `ToolFinder.toolInputRWs`.
 *
 * If these pass, a real production app can rely on `Sigil.instance` being
 * the one place to wire registration; if they fail, downstream serialization
 * (DB persistence, broadcast frames) is broken.
 */
class SigilWiringSpec extends AnyWordSpec with Matchers {
  // Per-suite DB path so each forked JVM gets its own RocksDB instance.
  TestSigil.initFor(getClass.getSimpleName)

  private def roundTripSignal[T <: Signal](value: T)(using rw: RW[Signal]): Signal =
    rw.write(rw.read(value))

  private def roundTripToolInput(value: ToolInput): ToolInput = {
    val rw = summon[RW[ToolInput]]
    rw.write(rw.read(value))
  }

  "Signal poly registration" should {
    "round-trip a Message" in {
      val original = Message(
        participantId = TestUser,
        conversationId = Conversation.id("c1"),
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("hello"))
      )
      val restored = roundTripSignal(original)
      restored shouldBe a[Message]
      restored.asInstanceOf[Message].content shouldBe original.content
    }

    "round-trip a ToolInvoke" in {
      val original = ToolInvoke(
        toolName = ToolName("respond"),
        participantId = TestUser,
        conversationId = Conversation.id("c1"),
        topicId = TestTopicId,
        input = Some(RespondInput(topic = "Chat", content = "▶Text\nhi", topicChangeType = TopicChangeType.NoChange))
      )
      val restored = roundTripSignal(original)
      restored shouldBe a[ToolInvoke]
      restored.asInstanceOf[ToolInvoke].toolName shouldBe ToolName("respond")
    }

    "round-trip a ModeChange" in {
      val original = ModeChange(
        mode = Mode.Coding,
        participantId = TestUser,
        conversationId = Conversation.id("c1"),
        topicId = TestTopicId
      )
      val restored = roundTripSignal(original)
      restored shouldBe a[ModeChange]
      restored.asInstanceOf[ModeChange].mode shouldBe Mode.Coding
    }

    "round-trip a MessageDelta" in {
      val msgId = sigil.event.Event.id()
      val original = MessageDelta(
        target = msgId,
        conversationId = Conversation.id("c1"),
        content = Some(ContentDelta(ContentKind.Text, None, complete = false, "abc")),
        usage = Some(TokenUsage(10, 20, 30)),
        state = Some(EventState.Complete)
      )
      val restored = roundTripSignal(original)
      restored shouldBe a[MessageDelta]
      val m = restored.asInstanceOf[MessageDelta]
      m.target shouldBe msgId
      m.content.map(_.delta) shouldBe Some("abc")
      m.usage.map(_.totalTokens) shouldBe Some(30)
      m.state shouldBe Some(EventState.Complete)
    }

    "round-trip a TopicChange (Switch)" in {
      val previous = Topic.id("prev")
      val original = TopicChange(
        kind = TopicChangeKind.Switch(previousTopicId = previous),
        newLabel = "Database Migration",
        participantId = TestUser,
        conversationId = Conversation.id("c1"),
        topicId = TestTopicId
      )
      val restored = roundTripSignal(original)
      restored shouldBe a[TopicChange]
      val tc = restored.asInstanceOf[TopicChange]
      tc.newLabel shouldBe "Database Migration"
      tc.kind shouldBe TopicChangeKind.Switch(previous)
    }

    "round-trip a TopicChange (Rename)" in {
      val original = TopicChange(
        kind = TopicChangeKind.Rename(previousLabel = "General"),
        newLabel = "Scala Coding Setup",
        participantId = TestUser,
        conversationId = Conversation.id("c1"),
        topicId = TestTopicId
      )
      val restored = roundTripSignal(original)
      restored shouldBe a[TopicChange]
      val tc = restored.asInstanceOf[TopicChange]
      tc.kind shouldBe TopicChangeKind.Rename("General")
    }

    "round-trip a ToolDelta carrying a parsed input" in {
      val toolId = sigil.event.Event.id()
      val original = ToolDelta(
        target = toolId,
        conversationId = Conversation.id("c1"),
        input = Some(ChangeModeInput(Mode.Coding, Some("user asked for code"))),
        state = Some(EventState.Complete)
      )
      val restored = roundTripSignal(original)
      restored shouldBe a[ToolDelta]
      val t = restored.asInstanceOf[ToolDelta]
      t.target shouldBe toolId
      t.state shouldBe Some(EventState.Complete)
      t.input.map(_.getClass.getSimpleName) shouldBe Some("ChangeModeInput")
    }
  }

  "ToolInput poly registration" should {
    "round-trip a RespondInput (core tool)" in {
      val original: ToolInput = RespondInput(
        topic = "Greetings",
        content = "▶Text\nhello",
        topicChangeType = TopicChangeType.NoChange
      )
      val restored = roundTripToolInput(original)
      restored shouldBe a[RespondInput]
      val r = restored.asInstanceOf[RespondInput]
      r.topic shouldBe "Greetings"
      r.content shouldBe "▶Text\nhello"
      r.topicChangeType shouldBe TopicChangeType.NoChange
    }

    "round-trip a RespondInput declaring a topic Change" in {
      val original: ToolInput = RespondInput(
        topic = "Database Migration",
        content = "▶Text\nswitching gears",
        topicChangeType = TopicChangeType.Change
      )
      val restored = roundTripToolInput(original)
      restored shouldBe a[RespondInput]
      val r = restored.asInstanceOf[RespondInput]
      r.topic shouldBe "Database Migration"
      r.topicChangeType shouldBe TopicChangeType.Change
    }

    "round-trip a RespondInput declaring a topic Update" in {
      val original: ToolInput = RespondInput(
        topic = "Python GIL",
        content = "▶Text\nnarrowing subject",
        topicChangeType = TopicChangeType.Update
      )
      val restored = roundTripToolInput(original)
      restored shouldBe a[RespondInput]
      val r = restored.asInstanceOf[RespondInput]
      r.topicChangeType shouldBe TopicChangeType.Update
    }

    "round-trip a ChangeModeInput (core tool)" in {
      val original: ToolInput = ChangeModeInput(Mode.Coding, None)
      val restored = roundTripToolInput(original)
      restored shouldBe a[ChangeModeInput]
      restored.asInstanceOf[ChangeModeInput].mode shouldBe Mode.Coding
    }

    "round-trip a SendSlackMessageInput (app-supplied via ToolFinder.toolInputRWs)" in {
      val original: ToolInput = SendSlackMessageInput("#engineering", "deploy done")
      val restored = roundTripToolInput(original)
      restored shouldBe a[SendSlackMessageInput]
      val r = restored.asInstanceOf[SendSlackMessageInput]
      r.channel shouldBe "#engineering"
      r.text shouldBe "deploy done"
    }
  }

  "Participant poly registration" should {
    "round-trip a DefaultAgentParticipant via the Participant poly RW" in {
      val rw = summon[RW[Participant]]
      val original: Participant = DefaultAgentParticipant(
        id = TestAgent,
        modelId = Model.id("test", "model"),
        toolNames = CoreTools.coreToolNames,
        instructions = Instructions(),
        generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0))
      )
      val restored = rw.write(rw.read(original))
      restored shouldBe a[DefaultAgentParticipant]
      val r = restored.asInstanceOf[DefaultAgentParticipant]
      r.id shouldBe TestAgent
      r.modelId shouldBe Model.id("test", "model")
      r.toolNames shouldBe CoreTools.coreToolNames
      r.generationSettings.maxOutputTokens shouldBe Some(200)
    }

    "round-trip a Conversation carrying a DefaultAgentParticipant" in {
      val rw = summon[RW[Conversation]]
      val agent = DefaultAgentParticipant(
        id = TestAgent,
        modelId = Model.id("test", "model"),
        toolNames = CoreTools.coreToolNames
      )
      val original = Conversation(
        currentTopicId = TestTopicId,
        participants = List(agent),
        currentMode = Mode.Coding,
        _id = Conversation.id("wire-test")
      )
      val restored = rw.write(rw.read(original))
      restored._id shouldBe original._id
      restored.currentTopicId shouldBe TestTopicId
      restored.currentMode shouldBe Mode.Coding
      restored.participants should have size 1
      restored.participants.head shouldBe a[DefaultAgentParticipant]
      restored.participants.head.asInstanceOf[DefaultAgentParticipant].toolNames shouldBe CoreTools.coreToolNames
    }
  }

  "Topic persistence" should {
    "round-trip a Topic record through the topics store" in {
      val topic = Topic(
        conversationId = Conversation.id("topic-persist-conv"),
        label = "Initial Topic",
        createdBy = TestUser
      )
      TestSigil.withDB(_.topics.transaction(_.upsert(topic))).sync()
      val loaded = TestSigil.withDB(_.topics.transaction(_.get(topic._id))).sync()
      loaded.isDefined shouldBe true
      loaded.get.label shouldBe "Initial Topic"
      loaded.get.conversationId shouldBe topic.conversationId
      loaded.get.createdBy shouldBe TestUser
    }

    "rehydrate Conversation.currentTopicId via newConversation" in {
      val conv = TestSigil.newConversation(
        createdBy = TestUser,
        label = "Bootstrap Topic",
        conversationId = Conversation.id("topic-bootstrap-conv")
      ).sync()
      val loadedTopic = TestSigil.withDB(_.topics.transaction(_.get(conv.currentTopicId))).sync()
      loadedTopic.isDefined shouldBe true
      loadedTopic.get.label shouldBe "Bootstrap Topic"
    }
  }

  "Sigil testMode flag" should {
    "be true on the test fixture so side-effectful tools can opt for stub responses" in {
      TestSigil.testMode shouldBe true
    }
  }
}
