package spec

import fabric.io.JsonParser
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ContextFrame, Conversation, ToolCallState, TurnInput}
import sigil.db.Model
import sigil.event.{Event, ToolInvoke, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.provider.openai.OpenAIProvider
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.{TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolResult}
import sigil.tool.core.{CoreTools, RespondTool}
import spice.http.HttpRequest

/**
 * Sigil #404 — a tool that legitimately FAILS (returns a well-formed
 * `ToolResult.Failure`, e.g. an ERP backend 502) must still settle its
 * `ToolInvoke` to a paired `role:tool` result, exactly as a `Success` does.
 * If the failed call doesn't get a matching tool-result in the next request,
 * OpenAI rejects with HTTP 400 "No tool output found for function call call_…"
 * and the conversation wedges (the same call id repeats on every retry).
 */
class FailingToolPairingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "failing-tool-model")
  private val callId = CallId("erp-failing-call")

  case class ErpInput() extends ToolInput derives RW
  ToolInput.register(RW.static(ErpInput()))

  /** A tool that returns a recoverable `ToolResult.failure` (never throws,
    * never returns empty) — mirrors Widge's `ErpToolSupport.execute`. */
  private case object ErpTool extends Tool {
    type Input  = ErpInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[ErpInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name = ToolName("erp_request")
    val description = "Test-only tool that returns a recoverable failure."
    override val keywords = Set("erp", "test")
    override def executeResult(input: ErpInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.failure("ERP request failed: upstream 502"))
  }

  private class ErpCallingProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "erp_request"),
        ProviderEvent.ToolCallComplete(callId, ErpInput()),
        ProviderEvent.Done(StopReason.Complete)
      ))
  }

  private def runAndPublish(convId: Id[Conversation]): Task[List[Signal]] = {
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId     = convId,
      model            = TestSigil.testModel(modelId),
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = Vector(ErpTool, RespondTool)
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new ErpCallingProvider, request, conv).toList
      _       <- signals.foldLeft(Task.unit)((acc, s) => acc.flatMap(_ => TestSigil.publish(s).map(_ => ())))
    } yield signals
  }

  "A failing tool call (#404)" should {

    "settle its ToolInvoke via a ToolDelta carrying a Failure outcome (state Complete)" in {
      runAndPublish(Conversation.id(s"erp-fail-${rapid.Unique()}")).map { signals =>
        val invoke = signals.collectFirst { case ti: ToolInvoke if ti.toolName == ToolName("erp_request") => ti }
          .getOrElse(fail(s"expected an erp_request ToolInvoke; saw ${signals.map(_.getClass.getSimpleName)}"))
        // The result-settle delta carries the Failure outcome (a separate
        // input-settle delta with state=Complete also exists — #265).
        val failureSettle = signals.collect {
          case d: ToolDelta if d.target == invoke._id && d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) => d
        }
        withClue(s"settling deltas for ${invoke._id.value}: ${signals.collect { case d: ToolDelta => d }}: ") {
          failureSettle should have size 1
          failureSettle.head.state shouldBe Some(EventState.Complete)
          failureSettle.head.outcome.collect { case f: ToolOutcome.Failure => f.reason }.get should include ("ERP request failed")
        }
      }
    }

    "leave the settled ToolInvoke at state Complete with a Failure outcome in the event log" in {
      val convId = Conversation.id(s"erp-fail-${rapid.Unique()}")
      runAndPublish(convId).flatMap { _ =>
        TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map { events =>
          val invoke = events.collectFirst { case ti: ToolInvoke if ti.toolName == ToolName("erp_request") => ti }
            .getOrElse(fail("no persisted erp_request ToolInvoke"))
          invoke.state shouldBe EventState.Complete
          invoke.outcome shouldBe a[ToolOutcome.Failure]
          // The settled invoke must project to a Complete ToolCall frame (the
          // paired tool-result half), not an Active/dangling one.
          val frame = sigil.conversation.FrameBuilder.computeFrame(invoke)
            .getOrElse(fail("settled failed invoke produced NO frame — it would render a dangling function_call"))
          frame match {
            case tc: ContextFrame.ToolCall =>
              tc.state shouldBe a[ToolCallState.Complete]
            case other => fail(s"expected a ToolCall frame, got $other")
          }
        }
      }
    }

    "render a function_call_output paired to the failed call in the next OpenAI request" in {
      val convId = Conversation.id(s"erp-fail-${rapid.Unique()}")
      runAndPublish(convId).flatMap { _ =>
        TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map { events =>
          val frames = events.flatMap(sigil.conversation.FrameBuilder.computeFrame).toVector
          val provider = OpenAIProvider(apiKey = "sk-test-placeholder", sigilRef = TestSigil)
          val req = ConversationRequest(
            conversationId     = convId,
            model            = TestSigil.testModel(Model.id("openai", "gpt-5.4-nano")),
            instructions       = Instructions(),
            turnInput          = TurnInput(conversationId = convId, frames = frames),
            currentMode        = ConversationMode,
            currentTopic       = TestTopicEntry,
            generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
            tools              = CoreTools.all,
            chain              = List(TestUser, TestAgent)
          )
          val body = provider.requestConverter(req).sync().content match {
            case Some(c: spice.http.content.StringContent) => c.value
            case _ => fail("expected StringContent body")
          }
          val items = JsonParser(body).get("input").collect { case a: fabric.Arr => a.value.toList }.getOrElse(Nil)
          def idsOfType(t: String): List[String] = items.collect {
            case it if it.get("type").map(_.asString).contains(t) => it.get("call_id").map(_.asString).getOrElse("")
          }
          val callIds   = idsOfType("function_call")
          val outputIds = idsOfType("function_call_output").toSet
          withClue(s"input items: ${items.map(_.get("type").map(_.asString))}; outputs=$outputIds: ") {
            // The failed call is present...
            callIds should contain (callId.value)
            // ...and EVERY function_call has a matching function_call_output —
            // no dangling call (the OpenAI 400 "No tool output found" cause).
            callIds.foreach(id => outputIds should contain (id))
          }
          succeed
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
