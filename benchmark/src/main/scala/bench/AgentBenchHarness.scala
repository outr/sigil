package bench

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.Sigil
import sigil.conversation.{Conversation, Topic}
import sigil.event.{AgentState, Event, Message, ModeChange, ToolInvoke}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.{ToolFinder, model}
import sigil.tool.model.ResponseContent

import scala.concurrent.duration.*

/**
 * Headless benchmark harness for agent-loop scenarios.
 *
 * Drives a [[Sigil]]-backed conversation programmatically: publish a
 * user [[Message]], wait for the agent's whole turn to settle in
 * `SigilDB.events`, capture every Event from that window into a
 * [[TurnTrace]], move on to the next turn. After all turns, returns a
 * [[ConversationTrace]] with the final persisted [[Conversation]].
 *
 * Mirrors `sigil.testkit.ConversationSession.send` minus the HTTP /
 * DurableSocket plumbing — benchmarks score against the persisted
 * event log, not the wire.
 *
 * Apps building benchmarks supply:
 *   - a configured `Sigil` (provider wired, agent + tool registrations
 *     in place — typically a `BenchmarkSigil` subclass that registers
 *     the participants / tools the benchmark exercises),
 *   - a viewer `ParticipantId` for the user-side messages,
 *   - a `Conversation` factory per scenario (with the appropriate
 *     agent participants and starting topic).
 *
 * The harness itself ships no scoring — each benchmark defines its own
 * rule over the trace shape (`ConversationTrace`).
 */
final class AgentBenchHarness(sigil: Sigil, viewer: ParticipantId) {

  /** Run a multi-turn conversation. Each user message in `userMessages`
    * is published; the harness waits for the agent's turn to settle
    * before publishing the next one. Returns the full
    * [[ConversationTrace]] including every settled event window plus
    * the final persisted [[Conversation]]. */
  def runConversation(conversationFactory: Id[Conversation] => Conversation,
                      userMessages: List[String],
                      perTurnTimeout: FiniteDuration = 60.seconds): Task[ConversationTrace] = {
    val convId = Conversation.id(s"bench-${rapid.Unique()}")
    val convo = conversationFactory(convId)
    val topicId = convo.topics.headOption.map(_.id).getOrElse(
      throw new IllegalStateException(
        "AgentBenchHarness: factory returned a Conversation with no topics; need at least one for `runTurn` to publish into"
      )
    )
    for {
      _ <- sigil.withDB(_.conversations.transaction(_.upsert(convo)))
      turns <- runTurns(convId, topicId, userMessages, perTurnTimeout, Nil)
      finalConv <- sigil.withDB(_.conversations.transaction(_.get(convId))).map(_.getOrElse(
        throw new NoSuchElementException(s"Conversation $convId not found after run")
      ))
    } yield ConversationTrace(convId, turns, finalConv)
  }

  /** Single-turn convenience — common shape for one-shot benchmarks
    * (BFCL, AgentDojo single-attack scenarios). */
  def runOneShot(conversationFactory: Id[Conversation] => Conversation,
                 userMessage: String,
                 timeout: FiniteDuration = 60.seconds): Task[ConversationTrace] =
    runConversation(conversationFactory, List(userMessage), timeout)

  /** Run `body` with `finder` installed as the active [[ToolFinder]],
    * restoring the previous finder on completion (success or failure).
    * Requires the wrapped Sigil to be a [[BenchmarkAgentSigil]] —
    * other Sigils don't expose a swappable tool catalog. Benchmarks
    * with per-scenario tools (AgentDojo banking, τ-bench retail) wrap
    * each scenario in this helper so each scenario's mutable env is
    * isolated from its neighbors. */
  def withToolFinder[A](finder: ToolFinder)(body: => Task[A]): Task[A] = sigil match {
    case agentSigil: BenchmarkAgentSigil =>
      val previous = agentSigil.setToolFinder(finder)
      body.attempt.flatMap { result =>
        agentSigil.setToolFinder(previous)
        result match {
          case scala.util.Success(a) => Task.pure(a)
          case scala.util.Failure(t) => Task.error(t)
        }
      }
    case _ =>
      Task.error(new IllegalStateException(
        "AgentBenchHarness.withToolFinder requires a BenchmarkAgentSigil — current Sigil does not expose a swappable ToolFinder"
      ))
  }

  private def runTurns(convId: Id[Conversation],
                       topicId: Id[Topic],
                       remaining: List[String],
                       timeout: FiniteDuration,
                       acc: List[TurnTrace]): Task[List[TurnTrace]] =
    remaining match {
      case Nil => Task.pure(acc.reverse)
      case head :: tail =>
        runTurn(convId, topicId, head, timeout).flatMap { trace =>
          runTurns(convId, topicId, tail, timeout, trace :: acc)
        }
    }

  private def runTurn(convId: Id[Conversation],
                      topicId: Id[Topic],
                      text: String,
                      timeout: FiniteDuration): Task[TurnTrace] = {
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
      windowEvents <- waitForAgentTurn(convId, after = now.value, timeout)
    } yield buildTrace(userMsg, windowEvents)
  }

  /** Poll `SigilDB.events` until an `AgentState(Complete)` exists for
    * this conversation newer than `after`, then return every Complete
    * event in the window (user message inclusive — `now` is the user
    * message's own timestamp, so `>= after` includes it).
    *
    * Two-phase wait: returning on the first agent Message would race a
    * multi-iteration loop where the agent fires a tool then chains
    * another. AgentState=Complete is the only marker that the loop has
    * fully released its claim. */
  private def waitForAgentTurn(convId: Id[Conversation], after: Long, timeout: FiniteDuration): Task[Vector[Event]] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Vector[Event]] = sigil.withDB(_.events.transaction(_.list)).flatMap { all =>
      val window = all
        .filter(e => e.conversationId == convId && e.timestamp.value >= after && e.state == EventState.Complete)
        .sortBy(_.timestamp.value)
        .toVector
      val agentSettled = window.exists(_.isInstanceOf[AgentState])
      if (agentSettled) Task.pure(window)
      else if (System.currentTimeMillis() < deadline) Task.sleep(200.millis).flatMap(_ => loop)
      else Task.error(new RuntimeException(
        s"AgentBenchHarness: agent turn did not settle in $convId within ${timeout.toSeconds}s — events seen so far: ${window.size}"
      ))
    }
    loop
  }

  private def buildTrace(userMsg: Message, window: Vector[Event]): TurnTrace = {
    val toolInvokes = window.collect { case t: ToolInvoke => t }
    val modeChanges = window.collect { case m: ModeChange => m }
    val finalReply = window.collect { case m: Message => m }
      .filter(m => m.participantId != viewer)
      .lastOption
    TurnTrace(userMsg, window, toolInvokes, modeChanges, finalReply)
  }
}

object AgentBenchHarness {
  def apply(sigil: Sigil, viewer: ParticipantId): AgentBenchHarness =
    new AgentBenchHarness(sigil, viewer)
}
