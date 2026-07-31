package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, ProgressCheckpoint, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic
import scala.concurrent.duration.*
import sigil.tool.consult.ProgressReflectionTool
import spec.MutatingSpecTool

/**
 * The mutation veto (#423) needs a verification condition: mutation ≠
 * progress when the same target is re-mutated with no verification
 * between rounds. Field shape: `edit_at_range` succeeded nine times
 * on the same lines of the same file in 3.5 minutes with no compile,
 * test, or diagnostics call anywhere — every checkpoint blessed it
 * (the reflector read "applied edits" as progress, and the veto would
 * have backed it), and only the user's kill ended the turn. The
 * per-window StallDetector tail cannot see this pattern: it resets at
 * every checkpoint.
 *
 * Verifies:
 *   1. Same-target mutations across ≥3 windows with no verification →
 *      an objective churn stall fires (intervention directive naming
 *      the pattern; checkpoints record no-progress) even though the
 *      reflector claims progress every time.
 *   2. A verification call in each window resets the chain — fix →
 *      verify → fix-again is legitimate iteration; no intervention.
 *   (Distinct-new-target bulk sweeps stay covered by
 *   CheckpointWindowProgressSpec.)
 */
class CheckpointChurnSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProgressCheckpointInterval(2)
  TestSigil.setMaxAgentIterations(8)

  override protected def afterAll(): Unit = {
    TestSigil.resetProgressCheckpointInterval()
    TestSigil.resetMaxAgentIterations()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "churn")
  TestSigil.testModel(modelId)

  /** Main-loop turns mutate the SAME target (distinct steps, so the
    * identical-call detector stays out of the way); when `verifyToo`
    * is set every other turn issues a verification call instead. The
    * reflector ALWAYS claims progress — the churn verdict must come
    * from the objective chain, not the reflector. The ceiling
    * forced-synthesis call gets a respond. */
  private final class SameTargetProvider(verifyToo: Boolean) extends Provider {
    val mainCalls = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId(s"call-${rapid.Unique()}")
      val emits: List[ProviderEvent] =
        if (input.tools.exists(_.name.value == "report_progress")) {
          List(
            ProviderEvent.ToolCallStart(callId, "report_progress"),
            ProviderEvent.toolCall(callId, ProgressReflectionTool)(_root_.sigil.tool.consult.ProgressReflectionInput(
              currentStatus      = s"Successfully applied edits, continuing (${rapid.Unique()}).",
              meaningfulProgress = true,
              remainingSteps     = "keep going",
              stuckOn            = None,
              shouldAskUser      = false
            )),
            ProviderEvent.Done(StopReason.Complete)
          )
        } else input.toolChoice match {
          case ToolChoice.Specific(name) if name == RespondTool.schema.name =>
            List(
              ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
              ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
                topicLabel = "Ceiling", topicSummary = "cap synthesis",
                content = "Synthesised at the iteration ceiling.", endsTurn = true)),
              ProviderEvent.Done(StopReason.Complete)
            )
          case _ =>
            val n = mainCalls.incrementAndGet()
            if (verifyToo && n % 2 == 0)
              List(
                ProviderEvent.ToolCallStart(callId, VerifyingSpecTool.name.value),
                ProviderEvent.toolCall(callId, VerifyingSpecTool)(VerifyingSpecInput(scope = s"check-$n")),
                ProviderEvent.Done(StopReason.ToolCall)
              )
            else
              List(
                ProviderEvent.ToolCallStart(callId, MutatingSpecTool.name.value),
                ProviderEvent.toolCall(callId, MutatingSpecTool)(MutatingSpecInput(
                  step = s"attempt-$n-${rapid.Unique()}",
                  target = Some("src/StandardBlockExtractor.scala"))),
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
      toolNames          = (CoreTools.coreToolNames :+ MutatingSpecTool.name) :+ VerifyingSpecTool.name,
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

  private def runTurn(provider: SameTargetProvider, suffix: String): Task[List[sigil.event.Event]] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"churn-$suffix-${rapid.Unique()}")
    val agent  = makeAgent()
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Repair the broken extractor file.")),
             state          = EventState.Complete
           ))
      _ <- waitUntil(30.seconds) {
        TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.exists {
          case m: Message =>
            m.conversationId == convId && m.participantId == TestAgent && m.content.collect {
              case t: ResponseContent.Text => t.text
              case md: ResponseContent.Markdown => md.text
            }.mkString.contains("iteration ceiling")
          case _ => false
        })
      }
      events <- TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.filter(_.conversationId == convId))
    } yield events
  }

  "the same-target churn guard" should {

    "fire an objective churn stall when the same target is re-mutated across windows with no verification" in {
      runTurn(new SameTargetProvider(verifyToo = false), suffix = "unverified").map { events =>
        val checkpoints = events.collect { case c: ProgressCheckpoint => c }.sortBy(_.iterationCount)
        val stallDirectives = events.collect {
          case m: Message if m.role == sigil.event.MessageRole.Tool && m.content.exists {
            case t: ResponseContent.Text => t.text.contains("without any compile/test/diagnostics")
            case _ => false
          } => m
        }
        withClue(s"checkpoints=${checkpoints.map(c => s"${c.iterationCount}:${c.meaningfulProgress}")} " +
          s"stallDirectives=${stallDirectives.size}: ") {
          // Windows at iterations 2 and 4 build the chain; the third
          // same-target unverified window (iteration 6) is churn.
          stallDirectives should not be empty
          checkpoints.filter(_.iterationCount >= 6).foreach(_.meaningfulProgress shouldBe false)
          // The directive names the target so the agent knows WHAT to verify.
          stallDirectives.head.content.collectFirst {
            case t: ResponseContent.Text => t.text
          }.get should include ("src/StandardBlockExtractor.scala")
        }
      }
    }

    "hold the veto when a verification call separates the rounds" in {
      runTurn(new SameTargetProvider(verifyToo = true), suffix = "verified").map { events =>
        val checkpoints = events.collect { case c: ProgressCheckpoint => c }
        val churnDirectives = events.collect {
          case m: Message if m.content.exists {
            case t: ResponseContent.Text => t.text.contains("without any compile/test/diagnostics")
            case _ => false
          } => m
        }
        withClue(s"checkpoints=${checkpoints.map(c => s"${c.iterationCount}:${c.meaningfulProgress}")}: ") {
          churnDirectives shouldBe empty
          checkpoints should not be empty
          checkpoints.count(_.meaningfulProgress) shouldBe checkpoints.size
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
