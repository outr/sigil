package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, ProgressCheckpoint, ToolInvoke, ToolOutcome}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic
import scala.concurrent.duration.*

/**
 * The progress checkpoint judges the window since the PRIOR
 * checkpoint, on evidence that includes objective mechanics — not the
 * whole-arc history head-capped to its first 20 lines. The frozen-arc
 * shape guaranteed that any turn longer than ~20 tool calls fed every
 * subsequent checkpoint byte-identical input: the reflector echoed the
 * same status verbatim, the "identical status = no progress" rule
 * converted the echo into strikes, and a healthy, actively-mutating
 * turn was force-ended mid-repair.
 *
 * Verifies:
 *   1. The reflection context is window-scoped: only calls since the
 *      prior checkpoint render, newest-first capped, with the earlier
 *      arc summarized as a count.
 *   2. Respond calls carry their `endsTurn` framing — a mid-task
 *      status update cannot read as the final reply.
 *   3. `windowMutations` counts exactly the successfully-settled
 *      destructive-annotated calls inside the window.
 *   4. End-to-end: a turn applying distinct successful mutations every
 *      iteration NEVER accumulates a no-progress streak — checkpoints
 *      record meaningfulProgress = true and no `_stall_detected`
 *      directive fires — even when the reflector reports no progress
 *      with an unchanging status. (Read-only churn stalling remains
 *      pinned by StallInterventionContinuesSpec.)
 */
class CheckpointWindowProgressSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProgressCheckpointInterval(2)
  TestSigil.setMaxAgentIterations(6)

  override protected def afterAll(): Unit = {
    TestSigil.resetProgressCheckpointInterval()
    TestSigil.resetMaxAgentIterations()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "window-progress")
  TestSigil.testModel(modelId)

  private def seedInvoke(convId: Id[Conversation],
                         name: String,
                         at: Long,
                         outcome: ToolOutcome = ToolOutcome.Success,
                         input: Option[sigil.tool.ToolInput] = None,
                         internal: Boolean = false): ToolInvoke =
    ToolInvoke(
      toolName       = ToolName(name),
      participantId  = TestAgent,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      timestamp      = Timestamp(at),
      state          = EventState.Complete,
      outcome        = outcome,
      internal       = internal,
      input          = input
    )

  /** Seed: objective user message at `base`, `preCount` invokes, a
    * settled checkpoint, then the supplied window events. */
  private def seedConversation(preCount: Int, window: List[Event]): Task[Id[Conversation]] = {
    val convId = Conversation.id(s"ckpt-window-${rapid.Unique()}")
    val base = lightdb.util.Nowish() - 100000L
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val objective = Message(
      participantId  = TestUser,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      content        = Vector(ResponseContent.Text("Remove all bug references from the code.")),
      state          = EventState.Complete,
      timestamp      = Timestamp(base)
    )
    val pre = (1 to preCount).toList.map(i => seedInvoke(convId, s"pre_tool_$i", base + i * 10))
    val checkpoint = ProgressCheckpoint(
      participantId        = TestAgent,
      conversationId       = convId,
      topicId              = TestTopicEntry.id,
      iterationCount       = 8,
      prevCheckpointStatus = None,
      currentStatus        = "swept the references",
      meaningfulProgress   = true,
      remainingSteps       = "repair fallout",
      stuckOn              = None,
      shouldAskUser        = false,
      timestamp            = Timestamp(base + 50000L),
      state                = EventState.Complete
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.withDB(_.eventsTransaction(convId) { tx =>
        (objective :: pre ::: checkpoint :: window).foldLeft(Task.unit)((a, e) => a.flatMap(_ => tx.upsert(e).unit))
      })
    } yield convId
  }

  private def windowAt(convId: Id[Conversation]): Long = lightdb.util.Nowish() - 40000L

  "the checkpoint reflection context" should {

    "scope the history to the window since the prior checkpoint and count the earlier arc" in {
      val convId = Conversation.id("placeholder")
      for {
        conv <- seedConversation(preCount = 25, window = Nil).flatMap { cid =>
          val at = windowAt(cid)
          val win = (1 to 5).toList.map(i => seedInvoke(cid, s"window_tool_$i", at + i * 10))
          TestSigil.withDB(_.eventsTransaction(cid)(tx => win.foldLeft(Task.unit)((a, e) => a.flatMap(_ => tx.upsert(e).unit)))).map(_ => cid)
        }
        ctx <- TestSigil.progressContextFor(conv, TestAgent)
      } yield {
        ctx.toolHistory should have size 5
        ctx.toolHistory.foreach(_ should startWith ("window_tool_"))
        ctx.toolHistory.exists(_.contains("pre_tool_")) shouldBe false
        ctx.earlierCalls shouldBe 25
      }
    }

    "keep the NEWEST calls when the window exceeds the line cap" in {
      for {
        conv <- seedConversation(preCount = 0, window = Nil).flatMap { cid =>
          val at = windowAt(cid)
          val win = (1 to 25).toList.map(i => seedInvoke(cid, s"win_$i", at + i * 10))
          TestSigil.withDB(_.eventsTransaction(cid)(tx => win.foldLeft(Task.unit)((a, e) => a.flatMap(_ => tx.upsert(e).unit)))).map(_ => cid)
        }
        ctx <- TestSigil.progressContextFor(conv, TestAgent)
      } yield {
        ctx.toolHistory should have size 20
        ctx.toolHistory.last should startWith ("win_25")
        ctx.toolHistory.exists(_.startsWith("win_1 ")) shouldBe false
        ctx.earlierCalls shouldBe 5
      }
    }

    "frame a mid-task respond as a status update and a final respond as the final reply" in {
      for {
        conv <- seedConversation(preCount = 0, window = Nil).flatMap { cid =>
          val at = windowAt(cid)
          val win = List(
            seedInvoke(cid, "respond", at + 10, input = Some(RespondInput(
              topicLabel = "t", topicSummary = "s",
              content = "Sweep done; now repairing the fallout.", endsTurn = false))),
            seedInvoke(cid, "respond", at + 20, input = Some(RespondInput(
              topicLabel = "t", topicSummary = "s",
              content = "All repaired.", endsTurn = true)))
          )
          TestSigil.withDB(_.eventsTransaction(cid)(tx => win.foldLeft(Task.unit)((a, e) => a.flatMap(_ => tx.upsert(e).unit)))).map(_ => cid)
        }
        ctx <- TestSigil.progressContextFor(conv, TestAgent)
      } yield {
        ctx.toolHistory should have size 2
        ctx.toolHistory.head should include ("mid-task status update, NOT a final reply")
        ctx.toolHistory.head should include ("Sweep done")
        ctx.toolHistory(1) should include ("final reply")
        ctx.toolHistory(1) should not include "NOT a final reply"
      }
    }

    "count exactly the window's successfully-settled destructive calls as mutations" in {
      for {
        conv <- seedConversation(preCount = 0, window = Nil).flatMap { cid =>
          val at = windowAt(cid)
          val win = List(
            seedInvoke(cid, MutatingSpecTool.name.value, at + 10),
            seedInvoke(cid, MutatingSpecTool.name.value, at + 20),
            seedInvoke(cid, MutatingSpecTool.name.value, at + 30,
              outcome = ToolOutcome.Failure("did not apply", recoverable = true)),
            seedInvoke(cid, "read_file", at + 40),
            seedInvoke(cid, "_stall_detected", at + 50, internal = true)
          )
          TestSigil.withDB(_.eventsTransaction(cid)(tx => win.foldLeft(Task.unit)((a, e) => a.flatMap(_ => tx.upsert(e).unit)))).map(_ => cid)
        }
        // Also a PRE-window mutation that must not count.
        _ <- TestSigil.withDB(_.eventsTransaction(conv)(_.upsert(
          seedInvoke(conv, MutatingSpecTool.name.value, lightdb.util.Nowish() - 90000L)).unit))
        ctx <- TestSigil.progressContextFor(conv, TestAgent)
      } yield {
        ctx.windowMutations shouldBe 2
        // Internal diagnostics never render as agent activity.
        ctx.toolHistory.exists(_.contains("_stall_detected")) shouldBe false
      }
    }
  }

  /** Emits a DISTINCT successful mutation every main-loop turn; the
    * reflector reports meaningfulProgress = false with an unchanging
    * "task completed" status (the #423 completion-latch shape). The
    * forced-synthesis call at the iteration ceiling gets a respond. */
  private final class MutatingButLatchedProvider extends Provider {
    val reflectorCalls = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId(s"call-${rapid.Unique()}")
      val emits: List[ProviderEvent] =
        if (input.tools.exists(_.name.value == "report_progress")) {
          reflectorCalls.incrementAndGet()
          List(
            ProviderEvent.ToolCallStart(callId, "report_progress"),
            ProviderEvent.ToolCallComplete(callId, _root_.sigil.tool.consult.ProgressReflectionInput(
              currentStatus      = "Task completed: references removed, final response delivered to user.",
              meaningfulProgress = false,
              remainingSteps     = "",
              stuckOn            = None,
              shouldAskUser      = false
            )),
            ProviderEvent.Done(StopReason.Complete)
          )
        } else input.toolChoice match {
          case ToolChoice.Specific(name) if name == RespondTool.schema.name =>
            List(
              ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
              ProviderEvent.ToolCallComplete(callId, RespondInput(
                topicLabel = "Ceiling", topicSummary = "cap synthesis",
                content = "Synthesised at the iteration ceiling.", endsTurn = true)),
              ProviderEvent.Done(StopReason.Complete)
            )
          case _ =>
            List(
              ProviderEvent.ToolCallStart(callId, MutatingSpecTool.name.value),
              ProviderEvent.ToolCallComplete(callId, MutatingSpecInput(step = s"repair-${rapid.Unique()}")),
              ProviderEvent.Done(StopReason.ToolCall)
            )
        }
      Stream.emits(emits)
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames :+ MutatingSpecTool.name,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def waitUntil(timeout: FiniteDuration)(cond: Task[Boolean]): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = cond.flatMap { ok =>
      if (ok || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    }
    loop
  }

  "the mechanical progress veto" should {

    "never strike a turn that applies distinct successful mutations, even when the reflector latches on completion" in {
      val provider = new MutatingButLatchedProvider
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"ckpt-veto-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("Repair the compile errors the sweep introduced.")),
               state          = EventState.Complete
             ))
        // The ceiling forced-synthesis respond ends the turn; wait for it.
        _ <- waitUntil(30.seconds) {
          TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.exists {
            case m: Message =>
              m.participantId == TestAgent && m.content.collect {
                case t: ResponseContent.Text => t.text
                case md: ResponseContent.Markdown => md.text
              }.mkString.contains("iteration ceiling")
            case _ => false
          })
        }
        events <- TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.filter(_.conversationId == convId))
      } yield {
        val checkpoints = events.collect { case c: ProgressCheckpoint => c }
        withClue(s"reflector ran ${provider.reflectorCalls.get()} times, " +
          s"checkpoints=${checkpoints.map(c => s"${c.iterationCount}:${c.meaningfulProgress}")}: ") {
          checkpoints should not be empty
          // Mechanical mutations veto the reflector's latched no-progress claim.
          checkpoints.foreach(_.meaningfulProgress shouldBe true)
        }
        // No stall directive ever fired against the progressing turn.
        events.collect { case ti: ToolInvoke if ti.toolName.value == "_stall_detected" => ti } shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
