package spec

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import profig.Profig
import rapid.Task
import sigil.{Sigil, SignalBroadcaster, TurnContext}
import sigil.conversation.{Conversation, ConversationContext}
import sigil.event.Event
import sigil.information.{FullInformation, Information}
import sigil.participant.{AgentParticipant, AgentParticipantId, Participant, ParticipantId}
import sigil.tool.{InMemoryToolFinder, Tool, ToolFinder, ToolInput}

import java.util.concurrent.atomic.AtomicReference

/**
 * Shared test Sigil — single instance reused across all specs to avoid
 * RocksDB lock contention (multiple Sigils opening the same DB path can't
 * hold the lock simultaneously) and to centralize the test fixtures
 * (tool catalog, synthetic tools, well-known participants).
 *
 * `testMode = true` so any side-effectful tools the tests reach can opt
 * for stub responses via `context.sigil.testMode`.
 */
object TestSigil extends Sigil {
  override def testMode: Boolean = true

  private val tools: List[Tool[? <: ToolInput]] = List(SendSlackMessageTool)

  override val findTools: ToolFinder = InMemoryToolFinder(tools)

  override def curate(ctx: ConversationContext): Task[ConversationContext] = Task.pure(ctx)

  // -- test-only mutable wiring for dispatcher specs --

  private val agentsRegistry = new java.util.concurrent.ConcurrentHashMap[AgentParticipantId, AgentParticipant]()
  private val broadcasterRef = new AtomicReference[SignalBroadcaster](SignalBroadcaster.NoOp)

  /** Register a test agent so `participantsFor` returns it for any
    * conversation. Cleared between tests via `resetAgents`. */
  def registerAgent(agent: AgentParticipant): Unit = {
    agentsRegistry.put(agent.id, agent)
    ()
  }

  def resetAgents(): Unit = agentsRegistry.clear()

  /** Replace the broadcaster for the current test (typically with a
    * `RecordingBroadcaster` to capture emissions). Reset to `NoOp` between
    * tests via `resetBroadcaster`. */
  def setBroadcaster(b: SignalBroadcaster): Unit = broadcasterRef.set(b)

  def resetBroadcaster(): Unit = broadcasterRef.set(SignalBroadcaster.NoOp)

  override def broadcaster: SignalBroadcaster = broadcasterRef.get()

  override def participantsFor(conversationId: Id[Conversation]): Task[List[Participant]] = Task {
    import scala.jdk.CollectionConverters.*
    agentsRegistry.values().asScala.toList
  }

  // getInformation uses Sigil's default (Task.pure(None)).

  /**
   * Register the test ParticipantId singletons so polymorphic serialization
   * of Messages / ToolInvokes / ModeChanges (which carry
   * `participantId: ParticipantId`) succeeds in tests.
   */
  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(TestUser), RW.static(TestAgent))

  /**
   * Initialize the test Sigil with a DB path scoped to the calling test
   * class. With `testGrouping` (one JVM per suite) plus per-suite paths,
   * each test exercises a fresh RocksDB instance — no lock contention, no
   * cross-suite state bleed.
   *
   * Wipes any leftover directory from a previous run before initializing,
   * so a crashed prior process can't leave stale RocksDB state that breaks
   * the next run. LightDB's own shutdown hook handles clean disposal on
   * normal JVM exit.
   *
   * Call once at the top of each spec, passing `getClass.getSimpleName`.
   */
  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = java.nio.file.Path.of("db", "test", name)
    deleteRecursive(dbPath)
    Profig.merge(obj("sigil" -> obj("dbPath" -> str(dbPath.toString))))
    instance.sync()
    ()
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit = {
    if (java.nio.file.Files.exists(path)) {
      val stream = java.nio.file.Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        stream
          .iterator()
          .asScala
          .toList
          .reverse
          .foreach(p => java.nio.file.Files.deleteIfExists(p))
      } finally stream.close()
    }
  }
}

/**
 * Synthetic tool exposed through [[TestSigil.findTools]]. Exists so the
 * `find_capability` flow has a real catalog entry to surface; its
 * `execute` returns no events because tests never actually invoke it.
 */
case class SendSlackMessageInput(channel: String, text: String) extends ToolInput derives RW

object SendSlackMessageTool extends Tool[SendSlackMessageInput] {
  override protected def uniqueName: String = "send_slack_message"
  override protected def description: String =
    "Send a message to a Slack channel on behalf of the user. Takes a channel name and the message text."
  override def execute(input: SendSlackMessageInput, context: TurnContext): rapid.Stream[Event] = rapid.Stream.empty
}

/**
 * Stand-in user participant for tests.
 */
case object TestUser extends ParticipantId {
  override val value: String = "test-user"
}

/**
 * Stand-in agent participant id for tests. Specs construct a concrete
 * [[sigil.participant.AgentParticipant]] carrying this id.
 */
case object TestAgent extends AgentParticipantId {
  override val value: String = "test-agent"
}
