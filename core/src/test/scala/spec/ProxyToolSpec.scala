package spec

import fabric.Json
import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream}
import sigil.TurnContext
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, Message, MessageVisibility, MessageRole}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent
import sigil.tool.proxy.{ProxyTool, ToolProxyTransport}

import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}

/**
 * Coverage for [[ProxyTool]] — verifies that the wrapper preserves
 * the wrapped tool's surface (name, description, schema, modes,
 * spaces, keywords, examples) and routes `execute` through the
 * supplied [[ToolProxyTransport]] with the typed input rendered to
 * fabric `Json`.
 */
class ProxyToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId   = Conversation.id("proxy-conv")
  private val topicId  = TestTopicId

  case class FakeToolInput(value: Int) extends ToolInput derives RW

  private case object FakeWrappedTool
    extends TypedTool[FakeToolInput](
      name = ToolName("fake_tool"),
      description = "Fake tool for proxy tests",
      examples = List(
        ToolExample("doubles its input", FakeToolInput(value = 5))
      )
    ) {
    override protected def executeTyped(input: FakeToolInput, context: TurnContext): Stream[Event] =
      Stream.emit(Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(s"local: ${input.value * 2}")),
        state = EventState.Complete
      ))
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
      val resultMessage = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = topicId,
        content = Vector(ResponseContent.Text("remote-ok")),
        state = EventState.Complete
      )
      transport.respondWith(Stream.emit(resultMessage))

      proxy.execute(FakeToolInput(value = 7), ctx).toList.map { events =>
        // Transport saw the typed input as Json
        val (_, capturedJson, _) = transport.lastCall.get()
        JsonFormatter.Compact(capturedJson) should include("\"value\":7")
        // Returned the transport's events unchanged
        events should have size 1
        events.head shouldBe resultMessage
      }
    }

    "pass the original ToolName through to the transport" in {
      val transport = new RecordingTransport
      val proxy     = new ProxyTool(FakeWrappedTool, transport)
      val ctx       = makeContext()
      transport.respondWith(Stream.empty)

      proxy.execute(FakeToolInput(value = 1), ctx).toList.map { _ =>
        val (capturedName, _, _) = transport.lastCall.get()
        capturedName shouldBe FakeWrappedTool.name
      }
    }
  }

  private def makeContext(): TurnContext = {
    // Lightweight context — we don't run the agent loop, just hand the proxy
    // something with a conversation/topic for the events it emits.
    val conv = Conversation(
      topics = List(sigil.conversation.TopicEntry(topicId, "test", "test")),
      _id = convId
    )
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      conversationView = sigil.conversation.ConversationView(conversationId = convId),
      turnInput = sigil.conversation.TurnInput(sigil.conversation.ConversationView(conversationId = convId))
    )
  }

  /** Test transport — records each dispatch call, replays a configured stream. */
  private class RecordingTransport extends ToolProxyTransport {
    private val response = new AtomicReference[Stream[Event]](Stream.empty)
    val lastCall: AtomicReference[(ToolName, Json, TurnContext)] =
      new AtomicReference[(ToolName, Json, TurnContext)]()
    val callCount = new AtomicInteger(0)

    def respondWith(s: Stream[Event]): Unit = response.set(s)

    override def dispatch(toolName: ToolName, inputJson: Json, context: TurnContext): Stream[Event] = {
      callCount.incrementAndGet()
      lastCall.set((toolName, inputJson, context))
      response.get()
    }
  }
}
