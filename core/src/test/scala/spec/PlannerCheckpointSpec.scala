package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ProgressCheckpoint, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant, ParticipantId}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.consult.{PlannerVerdictInput, ProgressReflectionInput}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import sigil.tool.consult.PlannerVerdictTool
import sigil.tool.consult.ProgressReflectionTool

/**
 * Planner-tier checkpoint: when `plannerModelId` is set, the
 * checkpoint's LLM step consults a higher-tier planner holding an
 * explicit plan artifact instead of asking the executor to assess
 * itself. Mechanical stall signals arm the planner rather than
 * driving the self-report streak machinery.
 *
 * Verifies:
 *   1. A deviating verdict publishes the `_planner_correction`
 *      directive and the loop continues so the executor acts on it.
 *   2. An on_track verdict overrides mechanical stall signals — a
 *      plan-holding model saying on_track is never stall-killed.
 *   3. Planner calls stay sparse on a healthy long turn (first-plan
 *      call + cadence ticks; never once per iteration).
 *   4. `plannerModelId = None` keeps today's behavior byte-identical:
 *      the reflector still runs, the planner is never consulted.
 *   5. The `_plan` directive is published on the first review and
 *      re-published on replan.
 */
class PlannerCheckpointSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProgressCheckpointInterval(2)
  TestSigil.setMaxAgentIterations(12)

  override protected def afterAll(): Unit = {
    TestSigil.resetProgressCheckpointInterval()
    TestSigil.resetMaxAgentIterations()
    TestSigil.resetPlannerModelId()
    TestSigil.resetPlannerCadence()
    super.afterAll()
  }

  private val executorModelId: Id[Model] = Model.id("test", "planner-executor")
  private val oversightModelId: Id[Model] = Model.id("test", "planner-oversight")
  TestSigil.testModel(executorModelId)
  TestSigil.testModel(oversightModelId)

  private object NoExtraction extends sigil.conversation.compression.extract.MemoryExtractor {
    override def extract(sigil: _root_.sigil.Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[_root_.sigil.conversation.ContextMemory]] =
      Task.pure(Nil)
  }

  private enum MainShape {
    case DistinctMutations
    case IdenticalReads
  }

  /** Scripted provider: planner consults answer from `verdicts` in
    * order (last repeats), reflector consults always claim progress,
    * and main-loop calls follow `mainShape` until `respondAfter` is
    * exhausted, then respond with `endsTurn = true`. */
  private final class PlannerScriptProvider(respondAfter: Int,
                                            mainShape: MainShape,
                                            verdicts: Vector[PlannerVerdictInput]) extends Provider {
    val totalCalls = new AtomicInteger(0)
    val mainCalls = new AtomicInteger(0)
    val plannerCalls = new AtomicInteger(0)
    val reflectorCalls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      totalCalls.incrementAndGet()
      val callId = CallId(s"call-${rapid.Unique()}")
      def respond(n: Int): List[ProviderEvent] = List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
          topicLabel   = TestTopicEntry.label,
          topicSummary = TestTopicEntry.summary,
          content      = s"Task complete after $n calls.",
          endsTurn     = true
        )),
        ProviderEvent.Done(StopReason.ToolCall)
      )
      val emits: List[ProviderEvent] =
        if (input.tools.exists(_.name.value == "planner_verdict")) {
          val n = plannerCalls.incrementAndGet()
          val v = verdicts.lift(n - 1).getOrElse(verdicts.last)
          List(
            ProviderEvent.ToolCallStart(callId, "planner_verdict"),
            ProviderEvent.toolCall(callId, PlannerVerdictTool)(v),
            ProviderEvent.Done(StopReason.Complete)
          )
        } else if (input.tools.exists(_.name.value == "report_progress")) {
          reflectorCalls.incrementAndGet()
          List(
            ProviderEvent.ToolCallStart(callId, "report_progress"),
            ProviderEvent.toolCall(callId, ProgressReflectionTool)(ProgressReflectionInput(
              currentStatus      = s"working (${rapid.Unique()})",
              meaningfulProgress = true,
              remainingSteps     = "keep going",
              stuckOn            = None,
              shouldAskUser      = false
            )),
            ProviderEvent.Done(StopReason.Complete)
          )
        } else input.toolChoice match {
          case ToolChoice.Specific(name) if name == RespondTool.schema.name => respond(mainCalls.get())
          case _ =>
            val n = mainCalls.incrementAndGet()
            if (n > respondAfter) respond(n)
            else mainShape match {
              case MainShape.DistinctMutations =>
                List(
                  ProviderEvent.ToolCallStart(callId, MutatingSpecTool.name.value),
                  ProviderEvent.toolCall(callId, MutatingSpecTool)(MutatingSpecInput(
                    step = s"step-$n-${rapid.Unique()}",
                    target = Some(s"src/File$n.scala"))),
                  ProviderEvent.Done(StopReason.ToolCall)
                )
              case MainShape.IdenticalReads =>
                List(
                  ProviderEvent.ToolCallStart(callId, GetMagicNumberTool.name.value),
                  ProviderEvent.toolCall(callId, GetMagicNumberTool)(GetMagicNumberInput()),
                  ProviderEvent.Done(StopReason.ToolCall)
                )
            }
        }
      Stream.emits(emits)
    }
  }

  private def onTrack(phase: String): PlannerVerdictInput = PlannerVerdictInput(
    verdict      = "on_track",
    correction   = "",
    objective    = "Repair the extractor and verify the build.",
    constraints  = List("Do not revert prior fixes."),
    doneCriteria = "Build compiles and tests pass.",
    currentPhase = phase
  )

  private def deviating(correction: String): PlannerVerdictInput =
    onTrack("applying fixes").copy(verdict = "deviating", correction = correction)

  private val replanned: PlannerVerdictInput = PlannerVerdictInput(
    verdict      = "replan",
    correction   = "",
    objective    = "Rewrite the extractor from scratch.",
    constraints  = Nil,
    doneCriteria = "New extractor passes the regression suite.",
    currentPhase = "rewriting"
  )

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = executorModelId,
      toolNames          = (CoreTools.coreToolNames :+ MutatingSpecTool.name) :+ GetMagicNumberTool.name,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def seedConv(suffix: String): Task[Id[Conversation]] = {
    val convId = Conversation.id(s"planner-$suffix-${rapid.Unique()}")
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)
    ))).map(_ => convId)
  }

  private def userMessage(convId: Id[Conversation], text: String): Message =
    Message(
      participantId  = TestUser,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      content        = Vector(ResponseContent.Text(text)),
      state          = EventState.Complete
    )

  private def waitFor(timeout: FiniteDuration)(cond: => Boolean): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] =
      if (cond || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    loop
  }

  private def eventsOf(convId: Id[Conversation]): Task[List[Event]] =
    TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.filter(_.conversationId == convId))

  private def invokesNamed(events: List[Event], name: String): List[ToolInvoke] =
    events.collect { case ti: ToolInvoke if ti.toolName.value == name => ti }

  private def directivesFor(events: List[Event], invokes: List[ToolInvoke]): List[String] = {
    val ids = invokes.map(_._id).toSet
    events.collect {
      case m: Message if m.role == MessageRole.Tool && m.origin.exists(ids.contains) =>
        m.content.collect { case t: ResponseContent.Text => t.text }.mkString
    }
  }

  private def agentReplyCount(convId: Id[Conversation]): Task[Int] =
    eventsOf(convId).map(_.count {
      case m: Message => m.participantId == TestAgent && m.role == MessageRole.Standard
      case _          => false
    })

  /** Run one full user turn to QUIESCENCE: a NEW agent reply landed
    * AND the provider stops being called (no increment across a
    * settle window). Early returns bleed a still-running loop into
    * the next test's provider (setProvider is by-name). */
  private def runTurn(provider: PlannerScriptProvider, convId: Id[Conversation], text: String): Task[Unit] = {
    TestSigil.setProvider(Task.pure(provider))
    TestSigil.setMemoryExtractor(NoExtraction)
    def quiesce(lastCalls: Int, stableMs: Long): Task[Unit] = {
      val cur = provider.totalCalls.get()
      if (cur == lastCalls && stableMs >= 600L) Task.unit
      else Task.sleep(200.millis).flatMap(_ => quiesce(cur, if (cur == lastCalls) stableMs + 200 else 0L))
    }
    for {
      repliesBefore <- agentReplyCount(convId)
      _ <- TestSigil.publish(userMessage(convId, text))
      _ <- waitFor(30.seconds)(agentReplyCount(convId).sync() > repliesBefore)
      _ <- quiesce(provider.totalCalls.get(), 0L)
    } yield ()
  }

  "the planner-tier checkpoint" should {

    "publish a correction directive on a deviating verdict and let the executor continue" in {
      TestSigil.setPlannerModelId(oversightModelId)
      val correction = "Stop rereading the file; apply the fix to Extractor.scala."
      val provider = new PlannerScriptProvider(
        respondAfter = 4,
        mainShape    = MainShape.DistinctMutations,
        verdicts     = Vector(deviating(correction))
      )
      for {
        convId <- seedConv("deviating")
        _ <- runTurn(provider, convId, "Repair the broken extractor file.")
        events <- eventsOf(convId)
      } yield {
        val corrections = invokesNamed(events, "_planner_correction")
        val checkpoints = events.collect { case c: ProgressCheckpoint => c }.sortBy(_.iterationCount)
        withClue(s"main=${provider.mainCalls.get()} planner=${provider.plannerCalls.get()} " +
          s"checkpoints=${checkpoints.map(c => s"${c.iterationCount}:${c.meaningfulProgress}")}: ") {
          corrections should have size 1
          directivesFor(events, corrections).head should include (correction)
          // The first planner review also created and published the plan.
          invokesNamed(events, "_plan") should have size 1
          // Non-terminal: the executor's next iterations ran and the
          // turn ended with its own respond, not a forced synthesis.
          provider.mainCalls.get() shouldBe 5
          checkpoints.head.meaningfulProgress shouldBe false
          invokesNamed(events, "_stall_detected") shouldBe empty
        }
      }
    }

    "trust an on_track verdict over mechanical stall signals" in {
      TestSigil.setPlannerModelId(oversightModelId)
      // Two extra repeats vs the original scenario: the duplicate-call
      // cap now refuses cache-served identical reads earlier (they
      // settle as real results), which pushes the identical-refusal
      // streak's threshold one checkpoint boundary later.
      val provider = new PlannerScriptProvider(
        respondAfter = 8,
        mainShape    = MainShape.IdenticalReads,
        verdicts     = Vector(onTrack("collecting the magic numbers"))
      )
      for {
        convId <- seedConv("ontrack")
        _ <- runTurn(provider, convId, "Fetch the magic numbers until done.")
        events <- eventsOf(convId)
      } yield {
        val checkpoints = events.collect { case c: ProgressCheckpoint => c }.sortBy(_.iterationCount)
        withClue(s"main=${provider.mainCalls.get()} planner=${provider.plannerCalls.get()} " +
          s"checkpoints=${checkpoints.map(c => s"${c.iterationCount}:${c.meaningfulProgress}")}: ") {
          // The identical-call streak armed the planner (second call at
          // iteration 6 fired on the anomaly, far below the cadence tick)…
          provider.plannerCalls.get() shouldBe 2
          // …but the plan-holding on_track verdict kept every checkpoint
          // meaningful and no stall intervention ever fired.
          checkpoints should not be empty
          checkpoints.count(_.meaningfulProgress) shouldBe checkpoints.size
          invokesNamed(events, "_stall_detected") shouldBe empty
          invokesNamed(events, "_planner_correction") shouldBe empty
          // The turn completed with the executor's own respond.
          provider.mainCalls.get() shouldBe 9
        }
      }
    }

    "keep planner calls sparse on a healthy long turn" in {
      TestSigil.setPlannerModelId(oversightModelId)
      TestSigil.setPlannerCadence(4)
      val provider = new PlannerScriptProvider(
        respondAfter = 8,
        mainShape    = MainShape.DistinctMutations,
        verdicts     = Vector(onTrack("phase 1"), onTrack("phase 2"), onTrack("phase 3"))
      )
      for {
        convId <- seedConv("sparse")
        _ <- runTurn(provider, convId, "Sweep every file and apply the rename.")
        events <- eventsOf(convId)
        _ = TestSigil.resetPlannerCadence()
      } yield {
        withClue(s"main=${provider.mainCalls.get()} planner=${provider.plannerCalls.get()}: ") {
          provider.mainCalls.get() shouldBe 9
          // First-plan call at iteration 2 plus one cadence tick at
          // iteration 6 — never once per boundary, let alone iteration.
          provider.plannerCalls.get() should (be >= 2 and be <= 3)
          provider.reflectorCalls.get() shouldBe 0
        }
      }
    }

    "leave the executor self-reflection path untouched when plannerModelId is unset" in {
      TestSigil.resetPlannerModelId()
      val provider = new PlannerScriptProvider(
        respondAfter = 4,
        mainShape    = MainShape.DistinctMutations,
        verdicts     = Vector(onTrack("unused"))
      )
      for {
        convId <- seedConv("disabled")
        _ <- runTurn(provider, convId, "Apply the fixes one by one.")
        events <- eventsOf(convId)
      } yield {
        val checkpoints = events.collect { case c: ProgressCheckpoint => c }
        withClue(s"reflector=${provider.reflectorCalls.get()} planner=${provider.plannerCalls.get()}: ") {
          provider.plannerCalls.get() shouldBe 0
          provider.reflectorCalls.get() should be >= 1
          checkpoints should not be empty
          invokesNamed(events, "_plan") shouldBe empty
          invokesNamed(events, "_planner_correction") shouldBe empty
        }
      }
    }

    "publish the plan on the first review and re-publish it on replan" in {
      TestSigil.setPlannerModelId(oversightModelId)
      TestSigil.setPlannerCadence(2)
      val provider = new PlannerScriptProvider(
        respondAfter = 4,
        mainShape    = MainShape.DistinctMutations,
        verdicts     = Vector(onTrack("phase 1"), replanned)
      )
      for {
        convId <- seedConv("replan")
        _ <- runTurn(provider, convId, "Repair the extractor.")
        events <- eventsOf(convId)
        _ = TestSigil.resetPlannerCadence()
        _ = TestSigil.resetPlannerModelId()
      } yield {
        val planInvokes = invokesNamed(events, "_plan")
        val planTexts = directivesFor(events, planInvokes)
        val checkpoints = events.collect { case c: ProgressCheckpoint => c }.sortBy(_.iterationCount)
        withClue(s"planner=${provider.plannerCalls.get()} planTexts=${planTexts.size} " +
          s"checkpoints=${checkpoints.map(_.currentStatus)}: ") {
          planInvokes should have size 2
          planTexts.exists(_.contains("Repair the extractor and verify the build.")) shouldBe true
          planTexts.exists(_.contains("Rewrite the extractor from scratch.")) shouldBe true
          checkpoints.count(_.meaningfulProgress) shouldBe checkpoints.size
          checkpoints.exists(_.currentStatus.contains("replanned")) shouldBe true
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
