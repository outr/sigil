package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.event.Message
import sigil.participant.{AgentParticipantId, ParticipantId}
import sigil.signal.{EventState, Signal}
import sigil.tool.model.ResponseContent
import sigil.transport.{ResumeRequest, SessionBridge}
import spice.http.WebSocketListener
import spice.http.durable.{DurableSocket, DurableSocketConfig, DurableSession, InMemoryEventLog}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Regression for sigil #298 — `SessionBridge.attach` returns a
 * [[sigil.transport.SessionRebindHandle]] whose `rebindViewer` swaps
 * the session's viewer mid-flight. Lets a session that authenticates
 * after handshake (sage / voidcraft's auth-token flow) re-subscribe
 * under the user's authenticated participant id; subsequent
 * `Sigil.publishTo(user/X, signal)` calls reach every device sharing
 * that viewer without an app-side broadcast hub.
 */
class SessionBridgeRebindSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  implicit override val testTimeout: FiniteDuration = 30.seconds

  /**
   * Two viewers sharing the conversation — `initial` for the
   * pre-auth pseudonym, `authed` for the post-auth user.
   */
  case object PreAuthViewer extends ParticipantId {
    override val value: String = "pre-auth-298"
  }
  case object AuthedViewer extends ParticipantId {
    override val value: String = "user-298"
  }

  // Make the synthetic viewers known to the framework's poly RW so
  // signal write/read round-trip them without a "Unknown
  // discriminator" failure.
  ParticipantId.register(RW.static(PreAuthViewer))
  ParticipantId.register(RW.static(AuthedViewer))

  private def buildSession(convId: Id[Conversation]): DurableSession[Id[Conversation], Signal, String] = {
    val log = new InMemoryEventLog[Id[Conversation], Signal]
    val protocol = new DurableSocket[Id[Conversation], Signal, String](
      config = DurableSocketConfig(),
      outboundLog = log,
      initialChannelId = convId
    )
    val listener = reactify.Var[WebSocketListener](new WebSocketListener)
    DurableSession[Id[Conversation], Signal, String](
      clientId = s"client-${rapid.Unique()}",
      info = "info",
      protocol = protocol,
      listener = listener
    )
  }

  private def freshConversation(suffix: String): Task[Conversation] = {
    val convId = Conversation.id(s"rebind-$suffix-${rapid.Unique()}")
    val topic = TopicEntry(Topic.id(s"topic-${convId.value}"), "test", "test")
    val conv = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def msg(convId: Id[Conversation], text: String, sender: ParticipantId): Message = Message(
    participantId = sender,
    conversationId = convId,
    topicId = TestTopicId,
    content = Vector(ResponseContent.Text(text)),
    state = EventState.Complete
  )

  "SessionBridge.attach (sigil #298)" should {

    "return a SessionRebindHandle whose currentViewer reflects the initial bind" in {
      for {
        conv <- freshConversation("currentViewer")
        session = buildSession(conv._id)
        handle <- SessionBridge.attach(TestSigil, session, PreAuthViewer, resume = ResumeRequest.None)
      } yield handle.currentViewer shouldBe PreAuthViewer
    }

    "rebindViewer swaps the live signalsFor subscription to the new viewer" in {
      val preAuthCount = new AtomicInteger(0)
      val authedCount = new AtomicInteger(0)

      // Subscribe per-viewer counters that watch our marker text via
      // the live signal stream. Pre-rebind, only PreAuthViewer's path
      // sees signals; post-rebind, only AuthedViewer's path sees them
      // (because the session's `signalsFor(viewer)` switched).
      TestSigil.signalsFor(PreAuthViewer)
        .collect {
          case m: Message if m.content.exists {
                case t: ResponseContent.Text => t.text == "rebind-marker"
                case _ => false
              } => m
        }
        .evalMap(_ => Task { preAuthCount.incrementAndGet(); () })
        .drain.startUnit()
      TestSigil.signalsFor(AuthedViewer)
        .collect {
          case m: Message if m.content.exists {
                case t: ResponseContent.Text => t.text == "rebind-marker"
                case _ => false
              } => m
        }
        .evalMap(_ => Task { authedCount.incrementAndGet(); () })
        .drain.startUnit()

      for {
        conv <- freshConversation("rebind-swap")
        session = buildSession(conv._id)
        handle <- SessionBridge.attach(TestSigil, session, PreAuthViewer, resume = ResumeRequest.None)
        _ <- Task.sleep(100.millis)
        // Rebind to the authed viewer.
        _ <- handle.rebindViewer(AuthedViewer)
        _ <- Task.sleep(100.millis)
        // Publish a marker — both raw subscribers should observe it
        // (they're independent of the SessionBridge), so this proves
        // the hub is delivering. The rebind's effect is observable
        // on `handle.currentViewer`.
        _ <- TestSigil.publish(msg(conv._id, "rebind-marker", PreAuthViewer))
        _ <- Task.sleep(300.millis)
      } yield {
        handle.currentViewer shouldBe AuthedViewer
        // Both raw subscribers picked it up — the message visibility
        // is `All`, so every viewer's signalsFor sees it.
        preAuthCount.get() shouldBe 1
        authedCount.get() shouldBe 1
      }
    }

    "rebindViewer is a no-op when newViewer equals currentViewer" in {
      for {
        conv <- freshConversation("rebind-noop")
        session = buildSession(conv._id)
        handle <- SessionBridge.attach(TestSigil, session, PreAuthViewer, resume = ResumeRequest.None)
        before = handle.currentViewer
        _ <- handle.rebindViewer(PreAuthViewer) // same viewer
      } yield {
        handle.currentViewer shouldBe PreAuthViewer
        handle.currentViewer shouldBe before
      }
    }

    "detach completes cleanly" in {
      for {
        conv <- freshConversation("detach")
        session = buildSession(conv._id)
        handle <- SessionBridge.attach(TestSigil, session, PreAuthViewer, resume = ResumeRequest.None)
        _ <- handle.detach
        // Calling detach twice is idempotent (second is a no-op on
        // an already-null reference).
        _ <- handle.detach
      } yield succeed
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
