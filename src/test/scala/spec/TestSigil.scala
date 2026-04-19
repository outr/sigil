package spec

import fabric.*
import fabric.rw.*
import profig.Profig
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.tool.{Tool, ToolContext, ToolInput}
import sigil.tool.core.ToolManager

/**
 * Shared test Sigil — single instance reused across all specs to avoid
 * RocksDB lock contention (multiple Sigils opening the same DB path can't
 * hold the lock simultaneously) and to centralize the test fixtures
 * (ToolManager catalog, synthetic tools, well-known participants).
 *
 * `testMode = true` so any side-effectful tools the tests reach can opt for
 * stub responses via `context.sigil.testMode`.
 */
object TestSigil extends Sigil {
  override val toolManager: ToolManager = TestToolManager
  override def testMode: Boolean = true

  /**
   * Register the test ParticipantId singletons so polymorphic serialization
   * of Messages / ToolInvokes / ModeChangedEvents (which carry
   * `participantId: ParticipantId`) succeeds in tests.
   */
  override protected def participantIds: List[RW[? <: ParticipantId]] = List(RW.static(TestUser))

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
 * In-memory tool catalog backing [[TestSigil]]. Keyword-matches incoming
 * `find_capability` queries against a small synthetic set so live-model
 * tests have something to discover.
 */
object TestToolManager extends ToolManager {
  private val catalog: List[(Set[String], Tool[? <: ToolInput])] = List(
    Set("slack", "message", "chat", "post") -> SendSlackMessageTool
  )

  override def find(query: String, participants: List[ParticipantId]): Task[List[Tool[? <: ToolInput]]] = Task {
    val q = query.toLowerCase
    catalog.collect { case (keywords, tool) if keywords.exists(q.contains) => tool }
  }

  override def all: List[Tool[? <: ToolInput]] = catalog.map(_._2)
}

/**
 * Synthetic tool registered with [[TestToolManager]]. Exists so the
 * `find_capability` flow has a real catalog entry to surface; its `execute`
 * returns no events because the tests never actually invoke it.
 */
case class SendSlackMessageInput(channel: String, text: String) extends ToolInput derives RW

object SendSlackMessageTool extends Tool[SendSlackMessageInput] {
  override protected def uniqueName: String = "send_slack_message"
  override protected def description: String =
    "Send a message to a Slack channel on behalf of the user. Takes a channel name and the message text."
  override def execute(input: SendSlackMessageInput, context: ToolContext): rapid.Stream[Event] = rapid.Stream.empty
}

/**
 * Stand-in user participant for tests.
 */
case object TestUser extends ParticipantId {
  override val value: String = "test-user"
}
