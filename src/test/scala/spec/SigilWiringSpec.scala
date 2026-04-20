package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.Conversation
import sigil.event.{Message, ModeChangedEvent, ToolInvoke}
import sigil.provider.{Mode, TokenUsage}
import sigil.signal.{ContentDelta, ContentKind, EventState, MessageDelta, Signal, ToolDelta}
import sigil.tool.ToolInput
import sigil.tool.model.{ChangeModeInput, RespondInput, ResponseContent}

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
        content = Vector(ResponseContent.Text("hello"))
      )
      val restored = roundTripSignal(original)
      restored shouldBe a[Message]
      restored.asInstanceOf[Message].content shouldBe original.content
    }

    "round-trip a ToolInvoke" in {
      val original = ToolInvoke(
        toolName = "respond",
        participantId = TestUser,
        conversationId = Conversation.id("c1"),
        input = Some(RespondInput("▶Text\nhi"))
      )
      val restored = roundTripSignal(original)
      restored shouldBe a[ToolInvoke]
      restored.asInstanceOf[ToolInvoke].toolName shouldBe "respond"
    }

    "round-trip a ModeChangedEvent" in {
      val original = ModeChangedEvent(
        mode = Mode.Coding,
        participantId = TestUser,
        conversationId = Conversation.id("c1")
      )
      val restored = roundTripSignal(original)
      restored shouldBe a[ModeChangedEvent]
      restored.asInstanceOf[ModeChangedEvent].mode shouldBe Mode.Coding
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
      val original: ToolInput = RespondInput("▶Text\nhello")
      val restored = roundTripToolInput(original)
      restored shouldBe a[RespondInput]
      restored.asInstanceOf[RespondInput].content shouldBe "▶Text\nhello"
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

  "Sigil testMode flag" should {
    "be true on the test fixture so side-effectful tools can opt for stub responses" in {
      TestSigil.testMode shouldBe true
    }
  }
}
