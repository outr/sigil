package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.{Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{DispatchRefusal, Message, MessageRole, ToolInvoke, ToolOutcome}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A model that keeps re-issuing the same call must be brought to a
 * close, not refused forever.
 *
 * The duplicate-call cap detects the repeat and refuses the dispatch,
 * and the refusal itself is a Tool-role Failure that re-triggers the
 * loop — so the cap is the only thing standing between a stubborn model
 * and the iteration ceiling. Refusing without ever terminating spends a
 * whole turn on refusals: one per iteration, each carrying an escalation
 * nudge, until the ceiling throws.
 *
 * The scripted model here re-issues a PAIR of calls verbatim on every
 * iteration — the field shape, and the one the consecutive-identical
 * hard-stall backstop cannot see (no two adjacent calls are identical).
 * The cap is therefore the only guard in play, and what it does with the
 * repeat is exactly what this spec measures:
 *
 *   - refusals of one (tool, args) group are BOUNDED and the turn moves
 *     to termination rather than emitting refusal N+1 forever;
 *   - the tier escalation rides the FIRST refusal of a group only;
 *   - the raced-result redirect — which exists for calls whose result
 *     arrived late — never fires on a refusal, which raced nothing.
 */
class DuplicateRefusalTerminationSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "refusal-termination")
  TestSigil.testModel(modelId)

  private val probes = List("alpha", "bravo")

  /** Iteration ceiling for the turn. High enough that an unbounded
    * refusal loop is visible as such, low enough that the pre-fix
    * grind doesn't dominate the suite's runtime. */
  private val iterationCap = 7

  /** Re-issues the same pair on every agent iteration, forever. Once the
    * roster no longer carries the probe tool the turn has been forced to
    * synthesis, and the model answers. Rosters carrying neither are
    * framework consults and get an empty completion. */
  private class StubbornProvider(recorded: ConcurrentLinkedQueue[ProviderCall],
                                 forcedSynthesis: AtomicInteger) extends Provider {
    private val iterations = new AtomicInteger(0)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))

    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val names = input.roster.tools.map(_.name).toSet
      if (names.contains(ProbeReadTool.name)) {
        recorded.add(input)
        val n = iterations.incrementAndGet()
        Stream.emits(probes.zipWithIndex.flatMap { case (probe, i) =>
          val cid = CallId(s"probe-$n-$i")
          List(
            ProviderEvent.ToolCallStart(cid, ProbeReadTool.name.value),
            ProviderEvent.toolCall(cid, ProbeReadTool)(ProbeReadInput(probe = probe))
          )
        } :+ ProviderEvent.Done(StopReason.ToolCall))
      } else if (names.contains(RespondTool.schema.name)) {
        recorded.add(input)
        forcedSynthesis.incrementAndGet()
        val cid = CallId(s"respond-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
          ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
            topicLabel = "Probes", topicSummary = "Stubborn repeat", content = "Here is what I have.", endsTurn = true)),
          ProviderEvent.Done(StopReason.Complete)
        ))
      } else Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
    }
  }

  private def agent: AgentParticipant = DefaultAgentParticipant(
    id = TestAgent,
    modelId = modelId,
    toolNames = ProbeReadTool.name :: CoreTools.coreToolNames,
    instructions = Instructions(),
    generationSettings = GenerationSettings(maxOutputTokens = Some(64), temperature = Some(0.0))
  )

  private case class Run(convId: Id[Conversation],
                         iterations: Int,
                         forcedSynthesis: Int,
                         probeInvokes: List[ToolInvoke],
                         toolNotes: List[String],
                         replies: List[String])

  private lazy val run: Run = {
    val recorded = new ConcurrentLinkedQueue[ProviderCall]()
    val forced = new AtomicInteger(0)
    TestSigil.setProvider(Task.pure(new StubbornProvider(recorded, forced)))
    TestSigil.resetTurnGovernors()
    TestSigil.setMaxAgentIterations(iterationCap)

    val convId = Conversation.id(s"refusal-termination-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    val task = for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Run the probes and report back.")),
             state          = sigil.signal.EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId, timeout = 120.seconds)
      events <- TestSigil.eventsFor(convId).map(_.events)
    } yield {
      val notes = events.collect {
        case m: Message if m.role == MessageRole.Tool => m
      }.flatMap(_.content.collect { case t: ResponseContent.Text => t.text })
      val replies = events.collect {
        case m: Message if m.role == MessageRole.Standard && m.participantId == TestAgent => m
      }.flatMap(_.content.collect {
        case t: ResponseContent.Text     => t.text
        case m: ResponseContent.Markdown => m.text
      })
      val probeInvokes = events.collect {
        case ti: ToolInvoke if ti.toolName == ProbeReadTool.name => ti
      }
      Run(convId, recorded.iterator().asScala.size, forced.get(), probeInvokes, notes, replies)
    }
    task.sync()
  }

  private def report: String =
    s"iterations=${run.iterations} forcedSynthesis=${run.forcedSynthesis}\n" +
      run.toolNotes.zipWithIndex.map { case (t, i) => f"${i + 1}%2d ${t.take(160).replace("\n", " ")}" }.mkString("\n") +
      s"\nreplies: ${run.replies.map(_.take(80).replace("\n", " ")).mkString(" | ")}"

  private def refusalsFor(probe: String): List[String] =
    run.toolNotes.filter(t => t.contains("Refused to dispatch") && t.contains(probe))

  /** Every framework note that answers a repeat with a corrective instead
    * of a result — the cap's refusal and the raced-result redirect alike. */
  private def correctives: List[String] =
    run.toolNotes.filter(t => t.contains("Refused to dispatch") || t.contains("has been issued"))

  "A model re-issuing the same call every iteration" should {

    "answer the repeat with a bounded number of correctives, not one per iteration" in {
      // Two call groups, each allowed `duplicateRefusalBound` correctives.
      // Beyond that the turn must be ending, not still answering.
      withClue(s"$report\n") {
        correctives.size should be <= (probes.size * TestSigil.duplicateRefusalLimit)
      }
      probes.foreach { probe =>
        withClue(s"refusals of `$probe`:\n$report\n") {
          refusalsFor(probe).size should be <= TestSigil.duplicateRefusalLimit
        }
      }
    }

    "end the turn through forced synthesis rather than grinding to the iteration cap" in {
      withClue(s"$report\n") {
        run.forcedSynthesis should be >= 1
        run.iterations should be < iterationCap
        run.toolNotes.exists(_.contains("reached the iteration cap")) shouldBe false
        run.replies.exists(_.contains("Here is what I have.")) shouldBe true
      }
    }

    "carry the tier-escalation nudge on the first refusal of a group only" in {
      probes.foreach { probe =>
        val nudges = refusalsFor(probe).count(t =>
          t.contains("Next iteration will run on the") || t.contains("Already on the top tier"))
        withClue(s"escalation nudges for `$probe`:\n$report\n") {
          nudges shouldBe 1
        }
      }
    }

    "never redirect a refused call to the raced-result path — a refusal raced nothing" in {
      withClue(s"$report\n") {
        run.toolNotes.exists(_.contains("its result kept arriving after the prompt was built")) shouldBe false
        run.toolNotes.exists(_.contains("still finishing")) shouldBe false
      }
      // What keeps the two apart on the durable row: a refused dispatch is
      // Pending like a raced one, and says so.
      val refused = run.probeInvokes.filter(_.refusal.nonEmpty)
      withClue(s"refused invokes: ${refused.map(i => i.outcome -> i.refusal)}\n$report\n") {
        refused should not be empty
        refused.foreach { ti =>
          ti.refusal shouldBe Some(DispatchRefusal.DuplicateCap)
          ti.outcome shouldBe ToolOutcome.Pending
        }
        run.probeInvokes.filter(_.refusal.isEmpty).foreach(_.outcome shouldBe ToolOutcome.Success)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.resetMaxAgentIterations()
      TestSigil.resetTurnGovernors()
      TestSigil.reset()
      TestSigil.shutdown.sync()
      succeed
    }
  }
}
