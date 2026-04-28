package sigil.testkit

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.Sigil
import sigil.conversation.{Conversation, Topic}
import sigil.event.{AgentState, Event, Message}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import spice.http.durable.DurableSocketClient

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * A live wire session opened by [[ConversationHarness#withClient]].
 *
 * Bundles the durable-socket client, the freshly-created conversation,
 * and a wire-side recorder. Provides convenience methods for the
 * common test-shaped operations: publish a user message and wait for
 * the agent's reply ([[send]]), or read the persisted post-turn
 * conversation state ([[conversation]]).
 */
final class ConversationSession(sigil: Sigil,
                                viewer: ParticipantId,
                                agentIds: Set[ParticipantId],
                                topicId: Id[Topic],
                                val client: DurableSocketClient[Id[Conversation], Event, String],
                                val convId: Id[Conversation],
                                val received: ConversationSession.Received) {

  /** Publish a user [[sigil.event.Message]] in this session's conversation and
    * wait until the agent's whole turn has settled in
    * `SigilDB.events` — i.e. an `AgentState(Complete)` exists for
    * this conversation newer than the user message, and at least one
    * `Complete` Message from a non-viewer participant exists in the
    * same window. Returns the agent's most-recent Complete Message.
    *
    * Two-phase check matters: returning on the first agent-published
    * Complete Message would race a multi-iteration loop (e.g. agent
    * fires `respond` then chains another tool). The next `send` would
    * then collide with the agent's still-held lock and never trigger.
    *
    * Also sanity-checks that the wire actually delivered an agent
    * Message: if the SigilDB-side check passes but the wire saw
    * nothing, the harness raises (broken transport). Skipped if the
    * conversation has no agent participants. */
  def send(text: String, timeout: FiniteDuration = 60.seconds): Task[Message] = {
    val now = Timestamp()
    val userMsg = Message(
      participantId = viewer,
      conversationId = convId,
      topicId = topicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      timestamp = now
    )
    for {
      _ <- sigil.publish(userMsg)
      reply <- waitForAgentTurn(after = now.value, timeout)
      _ <- if (agentIds.isEmpty || received.messagesFrom(agentIds).nonEmpty) Task.unit
           else Task.error(new RuntimeException(
             s"send: agent turn settled in SigilDB.events but no agent Message reached the wire — transport broken?"
           ))
    } yield reply
  }

  /** Fresh read of the persisted [[sigil.conversation.Conversation]] for this session.
    * The framework keeps `currentMode`, `topics`, `participants`, etc.
    * up to date as events flow through `Sigil.publish`, so this
    * reflects post-turn state (e.g. a `change_mode` settled by the
    * time `send` returns). */
  def conversation: Task[Conversation] =
    sigil.withDB(_.conversations.transaction(_.get(convId))).map(_.getOrElse(
      throw new NoSuchElementException(s"Conversation $convId not found")
    ))

  private def waitForAgentTurn(after: Long, timeout: FiniteDuration): Task[Message] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Message] = sigil.withDB(_.events.transaction(_.list)).flatMap { all =>
      val turnEvents = all.filter(e => e.conversationId == convId && e.timestamp.value > after)
      val agentSettled = turnEvents.exists {
        case a: AgentState => a.state == EventState.Complete
        case _ => false
      }
      lazy val message = turnEvents.collect { case m: Message => m }
        .filter(m => m.participantId != viewer && m.state == EventState.Complete)
        .sortBy(_.timestamp.value)
        .lastOption
      if (agentSettled && message.isDefined) Task.pure(message.get)
      else if (System.currentTimeMillis() < deadline) Task.sleep(200.millis).flatMap(_ => loop)
      else {
        val summary = turnEvents.map {
          case a: AgentState => s"AgentState(state=${a.state})"
          case m: Message => s"Message(from=${m.participantId.value}, state=${m.state}, content=${m.content.size})"
          case other => s"${other.getClass.getSimpleName}(p=${other.participantId.value})"
        }.mkString(", ")
        Task.error(new RuntimeException(
          s"Agent turn did not settle in $convId within ${timeout.toSeconds}s — " +
            s"agentSettled=$agentSettled, message=${message.isDefined}, turnEvents=[$summary]"
        ))
      }
    }
    loop
  }
}

object ConversationSession {
  /** Append-only recorder of every [[sigil.event.Event]] the wire client received
    * during this session's lifetime. */
  final class Received {
    private val ref = new AtomicReference[Vector[Event]](Vector.empty)
    def add(e: Event): Unit = { ref.updateAndGet(_ :+ e); () }
    def all: Vector[Event] = ref.get

    /** All Messages whose `participantId` is in `ids` — typically the
      * agent ids of the conversation. */
    def messagesFrom(ids: Set[ParticipantId]): Vector[Message] =
      all.collect { case m: Message if ids.contains(m.participantId) => m }
  }

  /** Concatenate the user-visible textual content of a Message into a
    * single trimmed string. Covers every [[ResponseContent]] variant
    * via an exhaustive match — adding a new variant to the enum will
    * surface a non-exhaustive-match warning here so the rendering rule
    * is decided deliberately. Convenience for assertion sites that
    * want to inspect what the user would see, regardless of which
    * tool the agent picked. */
  def textOf(m: Message): String =
    m.content.map {
      case ResponseContent.Text(text)                => text
      case ResponseContent.Markdown(text)            => text
      case ResponseContent.Heading(text)             => text
      case ResponseContent.Code(code, _)             => code
      case ResponseContent.Diff(diff, _)             => diff
      case ResponseContent.Citation(source, exc, _)  => exc.fold(source)(e => s"$source: $e")
      case ResponseContent.ItemList(items, _)        => items.mkString("\n")
      case ResponseContent.Table(headers, rows)      =>
        (headers :: rows).map(_.mkString(" | ")).mkString("\n")
      case ResponseContent.Link(url, label)          => s"$label ($url)"
      case ResponseContent.Image(url, alt)           => alt.fold(url.toString)(a => s"$a ($url)")
      case ResponseContent.Field(label, value, _)    => s"$label: $value"
      case ResponseContent.Options(prompt, opts, _)  =>
        s"$prompt\n" + opts.map(o => s"${o.label}: ${o.value}").mkString("\n")
      case ResponseContent.Failure(reason, _)        => reason
      case ResponseContent.TextInput(label, _, _, _) => label
      case ResponseContent.SecretInput(label, _, _)  => label
      case ResponseContent.SecretRef(_, label)       => label
      case ResponseContent.Divider                   => ""
    }.filter(_.nonEmpty).mkString("\n").trim
}
