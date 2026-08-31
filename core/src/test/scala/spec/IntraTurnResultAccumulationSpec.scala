package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, Complexity, ConversationWork, GenerationSettings, Instructions, ModelCandidate,
  Provider, ProviderCall, ProviderEvent, ProviderMessage, ProviderStrategy, ProviderType, StopReason
}
import sigil.tool.consult.{SummarizationInput, SummarizationTool}
import sigil.tool.core.{CoreTools, RequestEscalationInput, RequestEscalationTool, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A long agent turn must keep showing the model the tool results it
 * gathered. Intra-turn compaction folds OLD this-turn events into a
 * rolling summary; when it folds the newest ones instead, the model
 * sees the same state every iteration, re-issues the same call, and the
 * turn burns to the iteration cap with no reply.
 *
 * Every scenario runs the same script — repeat one call with identical
 * args, escalate the tier mid-turn so the routed model genuinely swaps,
 * then keep calling with fresh args — and inspects the rendered prompt
 * of each provider request:
 *
 *   1. compaction folding at every boundary must still leave the recent
 *      results in the prompt (the `RecentTail` window);
 *   2. compaction must be sized against the model the turn ROUTES to,
 *      not the agent's nominal default;
 *   3. with compaction inert, the prompt accumulates every result.
 */
class IntraTurnResultAccumulationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  implicit override val testTimeout: FiniteDuration = 120.seconds

  /**
   * Identical-args calls before the escalation.
   */
  private val duplicateCalls = 2

  /**
   * Fresh-args calls made AFTER the escalation — the ones whose
   * results must stay readable.
   */
  private val postCalls = 8

  /**
   * Iteration index (1-based) on which the agent escalates.
   */
  private val escalationCall = duplicateCalls + 1

  /**
   * [[StandardIntraTurnCompactor]]'s default `recentTailN`.
   */
  private val recentTail = 4

  private val roomyNominalId: Id[Model] = Model.id("test", "accum-nominal-roomy")
  private val tinyNominalId: Id[Model] = Model.id("test", "accum-nominal-tiny")
  private val lowTierId: Id[Model] = Model.id("test", "accum-tier-low")
  private val mediumTierId: Id[Model] = Model.id("test", "accum-tier-medium")
  private val highTierId: Id[Model] = Model.id("test", "accum-tier-high")

  // Roomy models throughout: the routed model always has plenty of
  // budget, so the curator never sheds and every exclusion observed
  // belongs to the compaction seam.
  List(roomyNominalId, lowTierId, mediumTierId, highTierId).foreach(TestSigil.testModel)
  // The agent's NOMINAL model, small enough that
  // `compressionTriggerTokens` (0.6 x contextLength) would arm
  // compaction within a handful of iterations if the framework sized
  // the decision against it.
  TestSigil.cache.merge(List(TestSigil.testModel(lowTierId).copy(
    contextLength = 2000L,
    canonicalSlug = tinyNominalId.value,
    name = tinyNominalId.value,
    _id = tinyNominalId
  ))).sync()

  private def probeText(probe: String): String = ProbeReadTool.resultTextFor(probe)

  private def postProbe(i: Int): String = s"post-$i"

  /**
   * Scripted provider driving the whole turn. Agent iterations are
   * recognised by the probe tool in the roster; the compactor's
   * summarization consult (recognised by its own tool) is answered with
   * a fixed summary so compaction completes without a live model.
   */
  private class ScriptedProvider(recorded: ConcurrentLinkedQueue[ProviderCall]) extends Provider {
    private val agentCalls = new AtomicInteger(0)
    val summaryConsults = new AtomicInteger(0)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))

    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val names = input.roster.tools.map(_.name).toSet
      if (names.contains(SummarizationTool.name)) {
        summaryConsults.incrementAndGet()
        val cid = CallId(s"summary-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(cid, SummarizationTool.name.value),
          ProviderEvent.toolCall(cid, SummarizationTool)(
            SummarizationInput(summary = "Earlier this turn the agent ran several probe reads.", tokenEstimate = 12)),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
      } else if (names.contains(ProbeReadTool.name)) {
        recorded.add(input)
        val n = agentCalls.incrementAndGet()
        val cid = CallId(s"agent-$n")
        if (n < escalationCall) probeStream(cid, "dup")
        else if (n == escalationCall) Stream.emits(List(
          ProviderEvent.ToolCallStart(cid, RequestEscalationTool.name.value),
          ProviderEvent.toolCall(cid, RequestEscalationTool)(
            RequestEscalationInput(reason = "the probe reads keep repeating; need a stronger model")),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
        else if (n <= escalationCall + postCalls) probeStream(cid, postProbe(n - escalationCall))
        else Stream.emits(List(
          ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
          ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
            topicLabel = "Probes",
            topicSummary = "Probe accumulation",
            content = "Done.",
            endsTurn = true)),
          ProviderEvent.Done(StopReason.Complete)
        ))
      } else Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
    }

    private def probeStream(cid: CallId, probe: String): Stream[ProviderEvent] = Stream.emits(List(
      ProviderEvent.ToolCallStart(cid, ProbeReadTool.name.value),
      ProviderEvent.toolCall(cid, ProbeReadTool)(ProbeReadInput(probe = probe)),
      ProviderEvent.Done(StopReason.ToolCall)
    ))
  }

  /**
   * Everything the model would actually read on this request.
   */
  private def renderedText(call: ProviderCall): String = {
    val body = call.messagesWithVolatileTail.iterator.map {
      case ProviderMessage.System(c) => c
      case ProviderMessage.User(content) => content.mkString(" ")
      case ProviderMessage.Assistant(c, tcs) => s"$c ${tcs.mkString(" ")}"
      case ProviderMessage.ToolResult(_, c) => c
      case other => other.toString
    }.mkString("\n")
    s"${call.system}\n$body"
  }

  private def routedStrategy: ProviderStrategy = ProviderStrategy.routed(
    default = List(
      ModelCandidate(lowTierId, supportedComplexity = Set(Complexity.Low)),
      ModelCandidate(mediumTierId, supportedComplexity = Set(Complexity.Medium)),
      ModelCandidate(highTierId, supportedComplexity = Set(Complexity.High))
    ),
    routes = Map.empty,
    inferWorkType = Some((_, _) => Task.pure(ConversationWork)),
    inferComplexity = Some((_, _) => Task.pure(Complexity.Low))
  )

  private def makeAgent(nominal: Id[Model]): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = nominal,
      toolNames = ProbeReadTool.name :: RequestEscalationTool.name :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(64), temperature = Some(0.0))
    )

  private case class RunResult(calls: List[ProviderCall], convId: Id[Conversation], summaryConsults: Int) {

    /**
     * Post-escalation probe results readable on the LAST request of
     * the turn — what the model still had to reason from at the end.
     */
    def visibleAtEnd: Vector[String] = {
      val text = renderedText(calls.last)
      (1 to postCalls).map(postProbe).filter(p => text.contains(probeText(p))).toVector
    }

    def renderedLengths: List[Int] = calls.map(c => renderedText(c).length)
  }

  private def runTurn(nominal: Id[Model], label: String): Task[RunResult] = {
    val recorded = new ConcurrentLinkedQueue[ProviderCall]()
    // The script is stateful across iterations, so `setProvider`'s
    // by-name argument must close over ONE already-constructed instance.
    val provider = new ScriptedProvider(recorded)
    TestSigil.setProvider(Task.pure(provider))
    TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(routedStrategy)))
    // Boundary governors run their own LLM consults; this spec measures
    // what the compaction seam does to the prompt, so keep the loop
    // free of unrelated round-trips.
    TestSigil.setTurnGovernors(Nil)
    TestSigil.setMaxAgentIterations(escalationCall + postCalls + 4)

    val convId = Conversation.id(s"$label-${rapid.Unique()}")
    val agent = makeAgent(nominal)
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("Run the probe reads and report back.")),
        state = sigil.signal.EventState.Complete
      ))
      _ <- TestSigil.awaitSettled(convId, timeout = 90.seconds)
    } yield RunResult(recorded.iterator().asScala.toList, convId, provider.summaryConsults.get())
  }

  /**
   * Shared preconditions: the script ran to completion and the
   * escalation really did swap the routed model mid-turn.
   */
  private def assertScenarioRan(result: RunResult, clue: String): Unit = {
    withClue(s"$clue — agent iterations recorded: ${result.calls.size}\n") {
      result.calls.size should be >= (escalationCall + postCalls)
    }
    val routedIds = result.calls.map(_.model._id.value)
    withClue(s"$clue — routed models per iteration: ${routedIds.mkString(", ")}\n") {
      routedIds.take(escalationCall).toSet should not equal routedIds.drop(escalationCall).toSet
    }
  }

  "Intra-turn compaction" should {

    "leave the recent-tail window of results in the prompt when it folds every boundary" in {
      // Fold at every iteration boundary — the harshest cadence an app
      // can configure. Even then the model must still see the
      // `recentTailN` most recent exchanges; anything less and it
      // re-issues work it already completed.
      TestSigil.intraTurnCompactorOverride.set(Some(
        AlwaysFoldingCompactor(TestSigil.compactionInvariants)))
      runTurn(roomyNominalId, "accum-folding").map { result =>
        TestSigil.intraTurnCompactorOverride.set(None)
        assertScenarioRan(result, "folding turn")
        withClue("the compactor must actually have folded something: ") {
          result.summaryConsults should be > 0
        }
        val expected = (postCalls - recentTail + 1 to postCalls).map(postProbe).toVector
        val kept = result.visibleAtEnd
        withClue(s"folding turn — final prompt kept ${kept.mkString(",")} " +
          s"but must still carry the last $recentTail results (${expected.mkString(",")})\n") {
          expected.filterNot(kept.contains) shouldBe empty
        }
      }
    }

    "size the fold against the routed model, not the agent's nominal default" in
      // The nominal model's window would arm compaction several
      // iterations in; the model this turn actually routes to has ample
      // headroom, so nothing should be folded and every result stays.
      runTurn(tinyNominalId, "accum-routed-sizing").map { result =>
        assertScenarioRan(result, "routed-sizing turn")
        withClue(s"routed-sizing turn — compaction fired ${result.summaryConsults} time(s) " +
          "against a routed model with plenty of headroom\n") {
          result.summaryConsults shouldBe 0
        }
        val missing = (1 to postCalls).map(postProbe).filterNot(result.visibleAtEnd.contains)
        withClue(s"routed-sizing turn — lost these results: ${missing.mkString(", ")}\n") {
          missing shouldBe empty
        }
        withClue(s"routed-sizing turn — rendered lengths: ${result.renderedLengths.mkString(", ")}\n") {
          result.renderedLengths.last should be > result.renderedLengths(escalationCall)
        }
      }

    "accumulate every result on a turn where compaction never arms (control)" in
      runTurn(roomyNominalId, "accum-control").map { result =>
        assertScenarioRan(result, "control turn")
        result.summaryConsults shouldBe 0
        val missing = (1 to postCalls).map(postProbe).filterNot(result.visibleAtEnd.contains)
        withClue(s"control turn — lost these results: ${missing.mkString(", ")}\n") {
          missing shouldBe empty
        }
        withClue(s"control turn — rendered lengths: ${result.renderedLengths.mkString(", ")}\n") {
          result.renderedLengths.last should be > result.renderedLengths(escalationCall)
        }
      }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.intraTurnCompactorOverride.set(None)
      TestSigil.resetMaxAgentIterations()
      TestSigil.resetTurnGovernors()
      TestSigil.reset()
      TestSigil.shutdown.map(_ => succeed)
    }
  }
}
