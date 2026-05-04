package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.Conversation
import sigil.pipeline.SignalHub
import sigil.signal.{
  ConversationCreated, ConversationDeleted, ConversationListSnapshot, ConversationSnapshot,
  RequestConversationList, RequestToolList, Signal, SwitchConversation, ToolListSnapshot
}
import sigil.tool.BuiltinKind

/**
 * Unit coverage for the [[sigil.signal.Notice]] subsystem:
 *   - Polymorphic round-trip via `RW[Signal]` for each new Notice subtype
 *   - [[SignalHub]] viewer routing — `emit` reaches all subscribers,
 *     `emitTo(viewer)` only reaches matching viewer-scoped subscriptions
 */
class NoticeSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def roundTrip(s: Signal): Signal = {
    val rw = summon[RW[Signal]]
    rw.write(rw.read(s))
  }

  "Notice polymorphic round-trip" should {
    "round-trip RequestConversationList" in {
      val original = RequestConversationList()
      roundTrip(original) shouldBe a[RequestConversationList]
    }
    "round-trip ConversationCreated" in {
      val original = ConversationCreated(Conversation.id("c1"), TestUser)
      val r = roundTrip(original).asInstanceOf[ConversationCreated]
      r.conversationId shouldBe Conversation.id("c1")
      r.createdBy shouldBe TestUser
    }
    "round-trip ConversationDeleted" in {
      val original = ConversationDeleted(Conversation.id("c1"))
      roundTrip(original).asInstanceOf[ConversationDeleted].conversationId shouldBe Conversation.id("c1")
    }
    "round-trip SwitchConversation" in {
      val original = SwitchConversation(Conversation.id("c1"))
      roundTrip(original).asInstanceOf[SwitchConversation].conversationId shouldBe Conversation.id("c1")
    }
    "round-trip RequestToolList without filters" in {
      val original = RequestToolList()
      val r = roundTrip(original).asInstanceOf[RequestToolList]
      r.spaces shouldBe None
      r.kinds shouldBe None
    }
    "round-trip RequestToolList with kind filter" in {
      val original = RequestToolList(kinds = Some(Set(BuiltinKind)))
      val r = roundTrip(original).asInstanceOf[RequestToolList]
      r.kinds.map(_.contains(BuiltinKind)) shouldBe Some(true)
    }
    "round-trip ToolListSnapshot (empty)" in {
      val original = ToolListSnapshot(Nil)
      roundTrip(original).asInstanceOf[ToolListSnapshot].tools shouldBe Nil
    }
  }

  "SignalHub viewer routing" should {
    "deliver broadcast emit() to every subscriber regardless of viewer" in {
      val hub = new SignalHub()
      val broadcast = hub.subscribe
      val scopedA = hub.subscribeFor(TestUser)
      val scopedB = hub.subscribeFor(TestAgent)

      val notice = RequestConversationList()
      hub.emit(notice)
      hub.close()

      broadcast.toList.sync() shouldBe List(notice)
      scopedA.toList.sync()   shouldBe List(notice)
      scopedB.toList.sync()   shouldBe List(notice)
    }

    "deliver emitTo(viewer) only to that viewer's scoped subscriber" in {
      val hub = new SignalHub()
      val broadcast = hub.subscribe
      val scopedA = hub.subscribeFor(TestUser)
      val scopedB = hub.subscribeFor(TestAgent)

      val notice = RequestConversationList()
      hub.emitTo(TestUser, notice)
      hub.close()

      // Targeted emit reaches only the matching viewer-scoped subscription;
      // broadcast subscribers (no viewer) and other-viewer subscriptions
      // do NOT receive it.
      broadcast.toList.sync() shouldBe empty
      scopedA.toList.sync()   shouldBe List(notice)
      scopedB.toList.sync()   shouldBe empty
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
