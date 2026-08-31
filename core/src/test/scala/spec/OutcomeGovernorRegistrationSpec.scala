package spec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageDisposition, MessageRole, ToolInvoke}
import sigil.governor.{OutcomeGovernor, OutcomeVerdict, TurnOutcome}
import sigil.orchestrator.{Directive, Orchestrator, SyntheticDiagnostic}
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings, Instructions, Provider,
  ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.tool.core.{NoResponseTool, RespondTool}
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.atomic

/**
 * An app-registered [[OutcomeGovernor]] participates in the drained-
 * iteration fold exactly like the built-ins: it reads the same
 * [[TurnOutcome]], every governor in the list is consulted (these
 * verdicts are complementary, not competing), and emissions land in the
 * turn's own stream in list order.
 */
class OutcomeGovernorRegistrationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  override protected def afterAll(): Unit = {
    TestSigil.resetOutcomeGovernors()
    super.afterAll()
  }

  /**
   * Records the outcome it was handed and emits the directive it was
   * built with, so both consultation and emission order are
   * observable.
   */
  final private class MarkerGovernor(override val name: String, directive: Directive) extends OutcomeGovernor {
    val seen: atomic.AtomicReference[Option[TurnOutcome]] = new atomic.AtomicReference(None)
    override def evaluate(outcome: TurnOutcome, host: _root_.sigil.Sigil): Task[OutcomeVerdict] = Task {
      seen.set(Some(outcome))
      OutcomeVerdict.Emit(SyntheticDiagnostic(
        directive,
        outcome.caller,
        outcome.conversationId,
        outcome.topicId,
        disposition = MessageDisposition.Success))
    }
  }

  /**
   * Reads the outcome but declines to act.
   */
  final private class SilentGovernor(override val name: String) extends OutcomeGovernor {
    val seen: atomic.AtomicReference[Option[TurnOutcome]] = new atomic.AtomicReference(None)
    override def evaluate(outcome: TurnOutcome, host: _root_.sigil.Sigil): Task[OutcomeVerdict] = Task {
      seen.set(Some(outcome))
      OutcomeVerdict.Proceed
    }
  }

  /**
   * Plain prose then `end_turn`, no tool call — the drift shape the
   * built-in plain-text guard acts on.
   */
  final private class PlainTextProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List[ProviderEvent](
        ProviderEvent.TextDelta("here is my answer in prose"),
        ProviderEvent.Done(StopReason.Complete)
      ))
  }

  private def runWith(suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"outcome-governor-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(Model.id("test", "outcome-governor")),
      instructions = Instructions(),
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      chain = List(TestUser, TestAgent),
      tools = Vector(NoResponseTool, RespondTool)
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new PlainTextProvider, request, conv).toList
    } yield signals
  }

  private def toolText(signals: List[Signal]): List[String] = signals.collect {
    case m: Message if m.role == MessageRole.Tool =>
      m.content.collect { case t: ResponseContent.Text => t.text }.mkString
  }

  "A registered OutcomeGovernor" should {

    "read the drained iteration's evidence, be consulted alongside every peer, and emit in list order" in {
      val first = new MarkerGovernor("marker-first", Directive.RepeatedQueryIntercept("marker"))
      val silent = new SilentGovernor("marker-silent")
      val last = new MarkerGovernor("marker-last", Directive.RefusalChallenge)
      TestSigil.setOutcomeGovernors(List(first, silent, last))
      runWith("ordering").map { signals =>
        // Every governor was consulted — a Proceed does not short-circuit
        // the peers behind it.
        silent.seen.get() shouldBe defined
        // Emission order follows list order.
        signals.collect { case ti: ToolInvoke => ti.toolName.value } shouldBe
          List(Directive.RepeatedQueryIntercept("marker").wireName, Directive.RefusalChallenge.wireName)
        // The evidence is the iteration's real outcome, distilled.
        val outcome = first.seen.get().getOrElse(fail("governor was not consulted"))
        outcome.stopReason shouldBe StopReason.Complete
        outcome.sawToolCall shouldBe false
        outcome.bufferedText shouldBe "here is my answer in prose"
        outcome.activeMessageCreated shouldBe false
        outcome.forceResponseSynthesis shouldBe false
        outcome.contextPressured shouldBe false
        // Each emission carries its paired Tool-role Message.
        toolText(signals) should have size 2
        succeed
      }
    }

    "leave the framework defaults in place when the roster is reset" in {
      TestSigil.resetOutcomeGovernors()
      TestSigil.outcomeGovernors.map(_.name) shouldBe
        List("plain-text-reply", "degenerate-generation", "turn-decision")
      runWith("defaults").map { signals =>
        signals.collect { case ti: ToolInvoke => ti.toolName.value } shouldBe
          List(Directive.PlainTextReply("prose").wireName)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
