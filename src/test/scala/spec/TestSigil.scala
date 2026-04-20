package spec

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import profig.Profig
import rapid.Task
import sigil.Sigil
import sigil.conversation.{Conversation, ConversationContext}
import sigil.event.Event
import sigil.information.{FullInformation, Information}
import sigil.participant.{AgentParticipantId, ParticipantId}
import sigil.TurnContext
import sigil.tool.{InMemoryToolFinder, Tool, ToolFinder, ToolInput}

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
