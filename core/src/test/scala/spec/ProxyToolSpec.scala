package spec

import fabric.*
import fabric.io.JsonFormatter
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.Conversation
import sigil.event.ToolOutcome
import sigil.signal.ToolDelta
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}
import sigil.tool.ToolContext
import sigil.tool.proxy.{ProxyTool, ToolProxyTransport}

import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
import sigil.event.Event

/**
 * Coverage for [[ProxyTool]] — verifies that the wrapper preserves
 * the wrapped tool's surface (name, description, schema, modes,
 * spaces, keywords, examples) and routes its resolution through the
 * supplied [[ToolProxyTransport]] with the typed input rendered to
 * fabric `Json`.
 */
class ProxyToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId   = Conversation.id("proxy-conv")
  private val topicId  = TestTopicId

  case class FakeToolInput(value: Int) extends ToolInput derives RW

  private case object FakeWrappedTool extends Tool {
    type Input  = FakeToolInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[FakeToolInput]]
    val outputRW = summon[RW[TextToolOutput]]

    val name        = ToolName("fake_tool")
    val description = "Fake tool for proxy tests"
    override val examples: List[ToolExample] = List(
      ToolExample("doubles its input", FakeToolInput(value = 5))
    )

    override def executeOutput(input: FakeToolInput, context: ToolContext): Task[TextToolOutput] =
      Task.pure(TextToolOutput(s"local: ${input.value * 2}"))
  }

  "ProxyTool" should {
    "preserve the wrapped tool's name, description, and schema" in rapid.Task {
      val transport = new RecordingTransport
      val proxy     = new ProxyTool(FakeWrappedTool, transport)
      proxy.name shouldBe FakeWrappedTool.name
      proxy.description shouldBe FakeWrappedTool.description
      proxy.schema.input shouldBe FakeWrappedTool.schema.input
      proxy.examples shouldBe FakeWrappedTool.examples
      proxy.modes shouldBe FakeWrappedTool.modes
    }

    "route execute through the transport with the input rendered to Json" in {
      val transport = new RecordingTransport
      val proxy     = new ProxyTool(FakeWrappedTool, transport)
      val ctx       = makeContext()
      // The transport's remote side returns a typed result as Json —
      // here a TextToolOutput-shaped payload.
      transport.respondWith(ToolResult.Success(obj("text" -> str("remote-ok"))))

      proxy.execute(FakeToolInput(value = 7), ctx, Event.id()).toList.map { events =>
        // Transport saw the typed input as Json.
        val (_, capturedJson, _) = transport.lastCall.get()
        JsonFormatter.Compact(capturedJson) should include("\"value\":7")
        // The framework built exactly one settling ToolDelta from the
        // transport's resolution, carrying the decoded payload.
        val results = events.collect {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) => d
        }
        results should have size 1
        results.head.output.collect { case TextToolOutput(t) => t } shouldBe Some("remote-ok")
      }
    }

    "pass the original ToolName through to the transport" in {
      val transport = new RecordingTransport
      val proxy     = new ProxyTool(FakeWrappedTool, transport)
      val ctx       = makeContext()
      transport.respondWith(ToolResult.Success(obj("text" -> str("ok"))))

      proxy.execute(FakeToolInput(value = 1), ctx, Event.id()).toList.map { _ =>
        val (capturedName, _, _) = transport.lastCall.get()
        capturedName shouldBe FakeWrappedTool.name
      }
    }
  }

  private def makeContext(): TurnContext = {
    // Lightweight context — we don't run the agent loop, just hand the proxy
    // something with a conversation/topic for the result event it builds.
    val conv = Conversation(
      topics = List(sigil.conversation.TopicEntry(topicId, "test", "test")),
      _id = convId
    )
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      turnInput = sigil.conversation.TurnInput(sigil.conversation.ConversationView(conversationId = convId))
    )
  }

  /** Test transport — records each dispatch call, replays a configured result. */
  private class RecordingTransport extends ToolProxyTransport {
    private val response = new AtomicReference[ToolResult[Json]](ToolResult.Success(obj()))
    val lastCall: AtomicReference[(ToolName, Json, ToolContext)] =
      new AtomicReference[(ToolName, Json, ToolContext)]()
    val callCount = new AtomicInteger(0)

    def respondWith(r: ToolResult[Json]): Unit = response.set(r)

    override def dispatch(toolName: ToolName, inputJson: Json, context: ToolContext): Task[ToolResult[Json]] = {
      callCount.incrementAndGet()
      lastCall.set((toolName, inputJson, context))
      Task.pure(response.get())
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
