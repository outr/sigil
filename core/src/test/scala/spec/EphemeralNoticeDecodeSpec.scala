package spec

import fabric.Json
import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.{Message, MessageRole}
import sigil.participant.ParticipantId
import sigil.signal.{ConversationCostUpdated, EventState, Notice, Signal}
import sigil.tool.model.ResponseContent
import sigil.transport.SessionBridge

import scala.collection.mutable

/**
 * Sigil #409 — `SessionBridge`'s ephemeral handler must not silently drop a
 * valid, registered `Notice`. The old `case _` collapsed "deserialized to a
 * non-Notice" and "decode threw" into one branch and awaited nothing, so an
 * app-defined `Notice` subtype arriving before the polymorphic registry was
 * populated hit the drop path intermittently. The handler now awaits
 * registration before decoding, and splits the failure cases so the reason is
 * visible.
 */
class EphemeralNoticeDecodeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private val recorded = mutable.ListBuffer.empty[Notice]

  private val host: Sigil = new Sigil {
    override type DB = sigil.db.DefaultSigilDB
    override protected def buildDB(directory: Option[java.nio.file.Path],
                                   storeManager: lightdb.store.CollectionManager,
                                   appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
      new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)
    override def modelResolver: sigil.provider.ModelResolver = _ => None
    override protected def participantIds: List[RW[? <: ParticipantId]] =
      List(RW.static(TestUser), RW.static(TestAgent))
    override def handleNotice(notice: Notice, fromViewer: ParticipantId): Task[Unit] =
      Task(recorded.synchronized { recorded += notice; () })
  }
  // Phase-1 only (no DB open) — populates the process-global RW[Signal] registry
  // so we can serialize test payloads with correct discriminators.
  host.polymorphicRegistrations.sync()

  private val signalRW: RW[Signal] = summon[RW[Signal]]
  private def dispatch(json: Json): Task[Unit] = SessionBridge.noticeOrWarn(host, TestUser)(json)

  "SessionBridge ephemeral decode (#409)" should {

    "dispatch a valid registered Notice to handleNotice" in {
      recorded.synchronized(recorded.clear())
      val notice: Notice = ConversationCostUpdated(Conversation.id("c409"), BigDecimal(1), BigDecimal(1))
      dispatch(signalRW.read(notice)).map { _ =>
        recorded.synchronized(recorded.toList).collect { case c: ConversationCostUpdated => c } should have size 1
      }
    }

    "NOT dispatch a Signal that deserializes to a non-Notice — and not throw" in {
      recorded.synchronized(recorded.clear())
      val msg: Signal = Message(
        participantId  = TestUser,
        conversationId = Conversation.id("c409"),
        topicId        = TestTopicEntry.id,
        content        = Vector(ResponseContent.Text("hi")),
        state          = EventState.Complete,
        role           = MessageRole.Standard
      )
      dispatch(signalRW.read(msg)).map(_ => recorded.synchronized(recorded.toList) shouldBe empty)
    }

    "NOT dispatch undecodable garbage — and not throw" in {
      recorded.synchronized(recorded.clear())
      val garbage: Json = fabric.obj("type" -> fabric.str("NoSuchSignalType"), "x" -> fabric.num(1))
      dispatch(garbage).map(_ => recorded.synchronized(recorded.toList) shouldBe empty)
    }
  }
}
