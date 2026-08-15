package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ParticipantProjection, RecentToolInvocation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions, Provider,
  ProviderCall, ProviderEvent, ProviderMessage, ProviderType, StopReason
}
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import sigil.tool.ToolInputCanonicalizer
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A dispatched tool call must leave exactly ONE entry in the acting
 * participant's `recentToolInvocations`, carrying the call's real
 * outcome. Every consumer of that window counts entries: the prompt's
 * "Repeated tool calls" digest reports the count back to the model, the
 * duplicate-call cap refuses the Nth identical call, and the
 * raced-reissue redirect counts never-resulted entries. An invoke that
 * lands twice — once as a Pending placeholder when its `Complete` state
 * delta publishes, once with the real outcome when the executor settles
 * it — doubles the digest and lets phantom placeholders trip the raced
 * redirect ahead of the cap that should have fired.
 */
class ToolInvocationProjectionAccountingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 120.seconds

  private val modelId: Id[Model] = Model.id("test", "projection-accounting")
  TestSigil.testModel(modelId)

  private val probe = "dup"

  /** Scripted turn: `identicalCalls` identical `probe_read` dispatches,
    * then `respond` to end it. Non-agent rosters (extractors, consults)
    * get an empty completion so no live model is needed. */
  private class ScriptedProvider(recorded: ConcurrentLinkedQueue[ProviderCall],
                                 identicalCalls: Int) extends Provider {
    private val iterations = new AtomicInteger(0)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))

    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (!input.roster.tools.map(_.name).toSet.contains(ProbeReadTool.name))
        Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
      else {
        recorded.add(input)
        val n = iterations.incrementAndGet()
        val cid = CallId(s"iteration-$n")
        if (n <= identicalCalls) Stream.emits(List(
          ProviderEvent.ToolCallStart(cid, ProbeReadTool.name.value),
          ProviderEvent.toolCall(cid, ProbeReadTool)(ProbeReadInput(probe = probe)),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
        else Stream.emits(List(
          ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
          ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
            topicLabel = "Probes", topicSummary = "Probe accounting", content = "Done.", endsTurn = true)),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
  }

  private def agent: AgentParticipant = DefaultAgentParticipant(
    id = TestAgent,
    modelId = modelId,
    toolNames = ProbeReadTool.name :: CoreTools.coreToolNames,
    instructions = Instructions(),
    generationSettings = GenerationSettings(maxOutputTokens = Some(64), temperature = Some(0.0))
  )

  private case class Run(convId: Id[Conversation], calls: List[ProviderCall])

  /** Everything the model actually reads on a request. */
  private def renderedText(call: ProviderCall): String = {
    val body = call.messagesWithVolatileTail.iterator.map {
      case ProviderMessage.System(c)         => c
      case ProviderMessage.User(content)     => content.mkString(" ")
      case ProviderMessage.Assistant(c, tcs) => s"$c ${tcs.mkString(" ")}"
      case ProviderMessage.ToolResult(_, c)  => c
      case other                             => other.toString
    }.mkString("\n")
    s"${call.system}\n$body"
  }

  private def runTurn(label: String, identicalCalls: Int): Task[Run] = {
    val recorded = new ConcurrentLinkedQueue[ProviderCall]()
    val provider = new ScriptedProvider(recorded, identicalCalls)
    TestSigil.setProvider(Task.pure(provider))
    // Boundary governors run their own consults; this spec measures the
    // projection the dispatch path writes, not governor behavior.
    TestSigil.setTurnGovernors(Nil)
    TestSigil.setMaxAgentIterations(identicalCalls + 4)

    val convId = Conversation.id(s"$label-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Run the probe and report back.")),
             state          = EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId, timeout = 90.seconds)
    } yield Run(convId, recorded.iterator().asScala.toList)
  }

  private def probeEntries(convId: Id[Conversation]): Task[List[RecentToolInvocation]] =
    TestSigil.projectionFor(TestAgent, convId)
      .map(_.recentToolInvocations.filter(_.toolName == ProbeReadTool.name))

  private def toolFailureTexts(convId: Id[Conversation]): Task[List[String]] =
    TestSigil.eventsFor(convId).map(_.events.collect {
      case m: Message if m.role == MessageRole.Tool && m.isFailure => m
    }.flatMap(_.content.collect { case t: ResponseContent.Text => t.text }))

  "A settled tool dispatch" should {

    "record exactly one recent-invocation entry carrying its real outcome" in {
      runTurn("accounting-single", identicalCalls = 1).flatMap { run =>
        for {
          entries <- probeEntries(run.convId)
          invokes <- TestSigil.eventsFor(run.convId).map(_.events.collect {
                       case t: ToolInvoke if t.toolName == ProbeReadTool.name => t
                     })
        } yield {
          withClue(s"recent-invocation entries for one dispatch: $entries\n") {
            entries.size shouldBe 1
          }
          entries.head.resulted shouldBe true
          entries.head.failed shouldBe false
          withClue(s"durable invokes: ${invokes.map(i => i._id.value -> i.outcome)}\n") {
            invokes.size shouldBe 1
            invokes.head.outcome shouldBe ToolOutcome.Success
            entries.head.invokeId shouldBe Some(invokes.head._id)
          }
          // A call made once is not a repeat: the next iteration's prompt
          // must carry no duplicate digest at all.
          val next = run.calls.drop(1)
          next should not be empty
          renderedText(next.head) should not include "Repeated tool calls"
        }
      }
    }

    "report the true repeat count in the prompt's duplicate digest" in {
      // Two identical settled calls must read as `2x`, not `4x`: the
      // digest is the didactic pressure the model calibrates against.
      runTurn("accounting-digest", identicalCalls = 2).map { run =>
        val afterBoth = run.calls.drop(2)
        withClue(s"provider iterations recorded: ${run.calls.size}\n") {
          afterBoth should not be empty
        }
        val rendered = renderedText(afterBoth.head)
        withClue(s"repeated-call lines: ${rendered.linesIterator.filter(_.contains("identical args")).mkString("; ")}\n") {
          rendered should include(s"`${ProbeReadTool.name.value}` called 2x with identical args")
        }
      }
    }

    "trip the duplicate-call cap on the third identical call, not the raced-result redirect" in {
      runTurn("accounting-cap", identicalCalls = 3).flatMap { run =>
        toolFailureTexts(run.convId).map { texts =>
          withClue(s"tool-role failures: $texts\n") {
            texts.exists(_.contains("Refused to dispatch")) shouldBe true
            // The raced-result redirect is non-escalating and would mask
            // the cap entirely; its wording must not appear.
            texts.exists(_.contains("times this turn")) shouldBe false
          }
        }
      }
    }
  }

  "A genuinely raced dispatch (result settled Pending)" should {

    "stay a never-resulted entry that the cap ignores and the raced redirect counts" in {
      // The placeholder shape: the invoke reaches `Complete` with a
      // `Pending` outcome — the agent saw a placeholder, never a result.
      val convId = Conversation.id(s"accounting-race-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val input = ProbeReadInput(probe = probe)

      def publishRacedInvoke(): Task[Unit] = {
        val invoke = ToolInvoke(
          toolName       = ProbeReadTool.name,
          participantId  = TestAgent,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          input          = Some(input),
          state          = EventState.Active
        )
        TestSigil.publish(invoke).flatMap(_ => TestSigil.publish(ToolDelta(
          target         = invoke._id,
          conversationId = convId,
          input          = Some(input),
          state          = Some(EventState.Complete)
        ))).unit
      }

      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _       <- publishRacedInvoke()
        _       <- publishRacedInvoke()
        entries <- probeEntries(convId)
        proj    <- TestSigil.projectionFor(TestAgent, convId)
        signals <- Orchestrator.process(
                     TestSigil,
                     new ScriptedProvider(new ConcurrentLinkedQueue[ProviderCall](), identicalCalls = 1),
                     ConversationRequest(
                       conversationId     = convId,
                       model              = TestSigil.testModel(modelId),
                       instructions       = Instructions(),
                       turnInput          = TurnInput(
                         conversationId         = convId,
                         participantProjections = Map(TestAgent -> proj)
                       ),
                       currentMode        = ConversationMode,
                       currentTopic       = TestTopicEntry,
                       generationSettings = GenerationSettings(maxOutputTokens = Some(64), temperature = Some(0.0)),
                       tools              = Vector(ProbeReadTool, RespondTool),
                       chain              = List(TestUser, TestAgent),
                       turnStartedAt      = Some(Timestamp(0L))
                     ),
                     conv
                   ).toList
      } yield {
        val hash = ToolInputCanonicalizer.argsHash(input)
        withClue(s"raced entries: $entries\n") {
          entries.size shouldBe 2
          entries.forall(_.resulted) shouldBe false
          entries.forall(_.argsHash == hash) shouldBe true
        }
        val texts = signals.collect { case m: Message if m.role == MessageRole.Tool && m.isFailure => m }
          .flatMap(_.content.collect { case t: ResponseContent.Text => t.text })
        withClue(s"raced-dispatch failures: $texts\n") {
          // #407 — the persistent racer is redirected...
          texts.exists(_.contains("times this turn")) shouldBe true
          // ...and #354 — the retry is never counted by the duplicate cap.
          texts.exists(_.contains("Refused to dispatch")) shouldBe false
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.resetMaxAgentIterations()
      TestSigil.resetTurnGovernors()
      TestSigil.reset()
      TestSigil.shutdown.map(_ => succeed)
    }
  }
}
