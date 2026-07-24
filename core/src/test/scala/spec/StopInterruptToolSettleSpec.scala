package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal}
import sigil.tool.core.CoreTools
import sigil.tool.{TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolResult}
import spice.http.HttpRequest

/**
 * Regression for the Stop-leaves-tool-running-forever bug surfaced by
 * Shopkeeper's wire log: a `browser_screenshot` ToolInvoke that was
 * in-flight when the user hit Stop never settled — it stayed
 * `state=Active`, `outcome=Pending` forever, so the UI showed it
 * running indefinitely.
 *
 * The live agent loop consumes the orchestrator stream as
 * `Orchestrator.process(...).takeWhile(_ => !stopFlag.force.get())`
 * (Sigil.scala). A FORCE Stop truncates the stream mid-flight. If the
 * cut lands after the invoke's `ToolInvoke` element but before its
 * paired settle element (the window during which a slow atomic tool —
 * a browser screenshot — is executing), the settle is dropped. The
 * orchestrator's `.guarantee`/reconcile backstop must still settle the
 * invoke; otherwise it dangles and poisons both the UI and the next
 * turn's wire render.
 *
 * This reproduces that exact shape: a slow atomic tool flips the force
 * flag while it executes, so `takeWhile` drops the settle element the
 * same way a real Stop would.
 */
class StopInterruptToolSettleSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "stop-interrupt-model")

  case class SlowInput() extends ToolInput derives RW
  ToolInput.register(RW.static(SlowInput()))

  /**
   * Force flag the live loop's `takeWhile` consults. The tool flips it
   * during execution to model a Stop arriving mid-tool.
   */
  private val forceFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * Atomic tool that, like `browser_screenshot`, succeeds with image
   * bytes. The Stop is injected by the consumer the instant the invoke
   * is emitted (see `runWithForceStop`), so the live `takeWhile(!force)`
   * drops this tool's settle element.
   */
  private case object SlowScreenshotTool extends Tool {
    type Input = SlowInput
    type Output = TextToolOutput
    val inputRW = summon[RW[SlowInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name = ToolName("slow_screenshot")
    val description = "Atomic screenshot tool; a Stop arrives just after its invoke is emitted."

    override def executeResult(input: SlowInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("screenshot-bytes")))
  }

  private def buildRequest(convId: Id[Conversation]): ConversationRequest =
    ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      previousTopics = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain = List(TestUser, TestAgent),
      tools = CoreTools.all.toVector :+ SlowScreenshotTool
    )

  private def seedConversation(convId: Id[Conversation]): Task[Conversation] = {
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_ => conv)
  }

  final private class SingleToolCallProvider(toolName: String, input: ToolInput) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(in: ProviderCall): Stream[ProviderEvent] = Stream.emits(List(
      ProviderEvent.ToolCallStart(CallId("shot-1"), toolName),
      ProviderEvent.ToolCallComplete(CallId("shot-1"), input),
      ProviderEvent.Done(StopReason.ToolCall)
    ))
  }

  /**
   * Consume the orchestrator stream the way the live `runAgentLoop`
   * does on a force Stop: `takeWhile(_ => !force.get())`, then run the
   * loop's post-drain stop-exit reconcile (`settleDanglingToolInvokes`)
   * exactly as `runAgentLoop` does when the stop flag is set.
   */
  private def runWithForceStop(convId: Id[Conversation],
                               conv: Conversation,
                               request: ConversationRequest): Task[List[Event]] =
    for {
      _ <- Orchestrator.process(TestSigil, new SingleToolCallProvider(SlowScreenshotTool.name.value, SlowInput()), request, conv)
        .takeWhile(_ => !forceFlag.get())
        .evalTap { s =>
          // Publish the element, then — the instant the invoke
          // lands — flip the force flag, exactly as a user hitting
          // Stop right after the tool starts would. `takeWhile`
          // then drops the tool's settle element on the next pull.
          TestSigil.publish(s).handleError(_ => Task.unit).map { _ =>
            s match {
              case t: ToolInvoke if t.toolName == SlowScreenshotTool.name => forceFlag.set(true)
              case _ => ()
            }
          }
        }
        .drain
        .handleError(_ => Task.unit)
      // The loop's stop-exit: settle any invoke the force-cut left dangling.
      _ <- TestSigil.settleDanglingToolInvokes(convId, TestAgent)
      evs <- TestSigil.withDB(_.events.transaction(_.list))
    } yield evs.filter(_.conversationId == convId)

  "a tool invoke in-flight when a force Stop truncates the stream" should {
    "still settle (reach Complete with a non-Pending outcome or a paired Tool message), not dangle forever" in {
      val convId = Conversation.id(s"stop-interrupt-${rapid.Unique()}")
      for {
        conv <- seedConversation(convId)
        evs <- runWithForceStop(convId, conv, buildRequest(convId))
      } yield {
        val invokes = evs.collect { case t: ToolInvoke if t.toolName == SlowScreenshotTool.name => t }
        withClue(s"expected the slow_screenshot ToolInvoke to be persisted; events: ${evs.map(_.getClass.getSimpleName)}\n") {
          invokes should not be empty
        }
        invokes.foreach { invoke =>
          val settled = invoke.outcome != ToolOutcome.Pending && invoke.state == EventState.Complete
          val pairedMessage = evs.exists {
            case m: Message if m.role == MessageRole.Tool && m.origin.contains(invoke._id) => true
            case _ => false
          }
          withClue(s"ToolInvoke ${invoke._id.value} dangled: state=${invoke.state}, outcome=${invoke.outcome}, pairedMessage=$pairedMessage — a Stop-interrupted tool must not stay Active+Pending forever\n") {
            (settled || pairedMessage) shouldBe true
          }
        }
        succeed
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
