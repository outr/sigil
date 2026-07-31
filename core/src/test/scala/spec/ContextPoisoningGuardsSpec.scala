package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}
import sigil.tool.ToolContext
import sigil.tool.core.NoResponseTool
import sigil.tool.model.{NoResponseInput, ResponseContent}
import spice.http.HttpRequest
import fabric.rw.*

/**
 * Locks the agent's context against framework chatter that leaves
 * dangling references the model can't act on:
 *
 *   - Duplicate tool calls within a single completion: the paired
 *     Tool-role Message for the duplicate INLINES the original call's
 *     result content rather than referencing it by call_id. The
 *     agent's frame projection then shows the same content for both
 *     calls — no "see that result" pointer.
 *
 *   - Synthetic placeholder text the framework emits when a tool
 *     produces no MessageRole.Tool event: phrased as a recoverable
 *     failure the agent can act on, not a developer-facing
 *     "please report it" diagnostic.
 */
class ContextPoisoningGuardsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "ctx-poison")

  case class EchoInput(text: String) extends ToolInput derives RW

  /** Tool that produces a typed text result echoing the input text.
    * Lets the test verify the duplicate inlines that exact text
    * rather than a reference. */
  private final class EchoTool(toolName: ToolName) extends Tool {
    type Input  = EchoInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[EchoInput]]
    val outputRW = summon[RW[TextToolOutput]]
    override val name: ToolName = toolName
    override val description: String = "Echo input"
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "echo"))
    )
    override def _id: Id[Tool] = Id[Tool](name.value)
    override def executeResult(input: EchoInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(s"echoed: ${input.text}")))
  }

  /** Provider that emits TWO identical tool calls back-to-back so
    * the dedup path fires for the second one. */
  private class TwoIdenticalCallsProvider(echoTool: EchoTool) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callA = CallId("call-a")
      val callB = CallId("call-b")
      val args = EchoInput("hello")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callA, "echo"),
        ProviderEvent.toolCall(callA, echoTool)(args),
        ProviderEvent.ToolCallStart(callB, "echo"),
        ProviderEvent.toolCall(callB, echoTool)(args),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  "Duplicate tool calls within a completion" should {

    "inline the original result content into the duplicate's paired Tool-role Message" in {
      val convId = Conversation.id(s"dedup-inline-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val echoTool = new EchoTool(ToolName("echo"))
      val request = ConversationRequest(
        conversationId     = convId,
        model            = TestSigil.testModel(modelId),
        instructions       = Instructions(),
        turnInput          = TurnInput(conversationId = convId),
        currentMode        = ConversationMode,
        currentTopic       = TestTopicEntry,
        previousTopics     = Nil,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        chain              = List(TestUser, TestAgent),
        tools              = Vector(echoTool)
      )
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, new TwoIdenticalCallsProvider(echoTool), request, conv).toList
      } yield {
        val invokes = signals.collect { case t: ToolInvoke => t }
        invokes should have size 2

        // The original invoke settles via a ToolDelta carrying the
        // typed payload `TextToolOutput("echoed: hello")`.
        val settledDeltas = signals.collect {
          case d: ToolDelta if d.conversationId == convId && d.outcome.contains(ToolOutcome.Success) => d
        }
        settledDeltas should not be empty
        val resultTexts = settledDeltas.flatMap(_.output).collect { case TextToolOutput(t) => t }
        resultTexts.exists(_.contains("echoed: hello")) shouldBe true

        // The dedup path emits one Tool-role Message for the duplicate
        // invoke; it INLINES the original call's rendered result rather
        // than referencing it by call_id.
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool && m.conversationId == convId => m
        }
        val rendered = toolMessages.flatMap(_.content).collect { case ResponseContent.Text(t) => t }
        rendered should not be empty
        all(rendered) should not include "see that result"
        all(rendered) should not include "(deduplicated:"
        // The original content "echoed: hello" should appear inlined in
        // the duplicate's paired Message.
        rendered.exists(_.contains("echoed: hello")) shouldBe true
      }
    }
  }

  "Dangling tool-call handling" should {

    "synthesize nothing for a dangling tool call — the wire-side orphan-heal is removed" in Task {
      // The typed tool-execution model pairs every tool call with a
      // result event by construction: atomic dispatch builds a
      // `ToolResults`, streaming `respond` emits one too, and a
      // provider stream that dies mid-args is settled+paired by
      // `settleOrphanToolInvoke`. So `Provider.renderFrames` no
      // longer fabricates a placeholder `function_call_output` for a
      // dangling call — it logs the framework bug loudly instead.
      // Nothing the agent reads can be poisoned because nothing is
      // synthesized at all (sigil bug #189 family — closed for good).
      val src = scala.io.Source.fromFile(
        "core/src/main/scala/sigil/provider/Provider.scala"
      ).getLines().mkString("\n")
      // Neither the old prose placeholders NOR the structured orphan
      // marker exist any more — the synthesis path is gone entirely.
      src should not include "Please report it"
      src should not include "framework error: tool emitted no MessageRole.Tool"
      src should not include "The previous tool call did not return a result"
      src should not include "tool failed: no result emitted"
      src should not include "_sigil_orphan_marker"
      src should not include "_sigil_orphan_wireId"
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
