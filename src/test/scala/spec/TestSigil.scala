package spec

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import org.scalactic.Prettifier.default
import profig.Profig
import rapid.Task
import sigil.{Sigil, SignalBroadcaster, TurnContext}
import sigil.conversation.MemorySpaceId
import sigil.db.Model
import sigil.event.Event
import sigil.participant.{AgentParticipantId, ParticipantId}
import sigil.provider.Provider
import sigil.tool.{InMemoryToolFinder, Tool, ToolFinder, ToolInput}
import sigil.tool.core.CoreTools
import spice.net.*

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

  lazy val llamaCppHost: URL = Profig("sigil.llamacpp.host").asOr[URL](url"http://localhost:8081")

  // Core tools + the synthetic SendSlackMessageTool. Agents reference them
  // by name; `byName` resolves from this catalog at call time.
  // Tests opt into the non-core utility tools explicitly. `SleepTool`
  // backs timing-sensitive dispatcher tests; `LookupInformationTool`
  // backs the CoreToolsSpec information-resolution coverage.
  private val appTools: List[Tool[? <: ToolInput]] = List(
    SendSlackMessageTool,
    sigil.tool.util.SleepTool,
    sigil.tool.util.LookupInformationTool
  )

  override val findTools: ToolFinder =
    InMemoryToolFinder(CoreTools.all.toList ++ appTools)

  // TestSigil uses Sigil's default curator (identity — no memory/summary/info selection).

  // -- test-only mutable wiring --

  private val broadcasterRef = new AtomicReference[SignalBroadcaster](SignalBroadcaster.NoOp)
  private val providerRef = new AtomicReference[() => Task[Provider]](
    () => Task.error(new RuntimeException("TestSigil.setProvider was not called — no provider configured"))
  )
  private val informationRef = new AtomicReference[sigil.information.InMemoryInformation](new sigil.information.InMemoryInformation)
  private val modeSkillRef = new AtomicReference[sigil.provider.Mode => Task[Option[sigil.conversation.ActiveSkillSlot]]](
    _ => Task.pure(None)
  )

  /** Replace the broadcaster for the current test (typically with a
    * `RecordingBroadcaster` to capture emissions). */
  def setBroadcaster(b: SignalBroadcaster): Unit = broadcasterRef.set(b)

  def resetBroadcaster(): Unit = broadcasterRef.set(SignalBroadcaster.NoOp)

  override def broadcaster: SignalBroadcaster = broadcasterRef.get()

  /** Set the Provider that `providerFor` returns. Taken by-name so specs
    * can wire `setProvider(provider)` from a trait body even though the
    * subclass's `provider` val isn't yet initialized — evaluation defers
    * until `providerFor` is actually called. */
  def setProvider(p: => Task[Provider]): Unit = providerRef.set(() => p)

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    providerRef.get().apply()

  /** Expose an in-memory information store specs can populate before
    * exercising [[sigil.tool.util.LookupInformationTool]]. */
  def information: sigil.information.InMemoryInformation = informationRef.get()

  override def getInformation(id: lightdb.id.Id[sigil.information.Information]): Task[Option[sigil.information.Information]] =
    informationRef.get().get(id)

  /** Override the `modeSkill` hook for a test — returns the resolved slot
    * (or None) for the given mode. Default is always None. */
  def setModeSkill(f: sigil.provider.Mode => Task[Option[sigil.conversation.ActiveSkillSlot]]): Unit =
    modeSkillRef.set(f)

  def resetModeSkill(): Unit = modeSkillRef.set(_ => Task.pure(None))

  override def modeSkill(mode: sigil.provider.Mode): Task[Option[sigil.conversation.ActiveSkillSlot]] =
    modeSkillRef.get()(mode)

  /**
   * Register the test ParticipantId singletons so polymorphic serialization
   * of Messages / ToolInvokes / ModeChanges (which carry
   * `participantId: ParticipantId`) succeeds in tests.
   */
  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(TestUser), RW.static(TestAgent))

  /** Register TestSpace so ContextMemory's spaceId poly round-trips in
    * the memories store. */
  override protected def memorySpaceIds: List[RW[? <: MemorySpaceId]] =
    List(RW.static(TestSpace))

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
