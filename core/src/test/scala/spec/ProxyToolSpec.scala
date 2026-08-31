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
import sigil.tool.{
  ConsentSpec,
  DiscoverySpec,
  Effect,
  Execution,
  MutationTargeting,
  ProgressContract,
  Resolution,
  TextToolOutput,
  Tool,
  ToolExample,
  ToolGates,
  ToolIO,
  ToolInput,
  ToolName,
  ToolPrecondition,
  ToolPreconditionResult,
  ToolProfile,
  ToolResult,
  ToolSpec
}
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

  private val convId = Conversation.id("proxy-conv")
  private val topicId = TestTopicId

  case class FakeToolInput(value: Int) extends ToolInput derives RW

  private case object FakeWrappedTool extends Tool {
    type Input = FakeToolInput
    type Output = TextToolOutput
    val io: ToolIO[FakeToolInput, TextToolOutput] = ToolIO.derived[FakeToolInput, TextToolOutput].withExamples(
      ToolExample("doubles its input", FakeToolInput(value = 5))
    )

    override val name = ToolName("fake_tool")
    override val description = "Fake tool for proxy tests"
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "fake"))
    )

    protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

    private def executeOutput(input: FakeToolInput, context: ToolContext): Task[TextToolOutput] =
      Task.pure(TextToolOutput(s"local: ${input.value * 2}"))
  }

  private object OfflineDaemonPrecondition extends ToolPrecondition {
    val name: String = "remote-daemon"
    override def check(context: TurnContext): Task[ToolPreconditionResult] =
      Task.pure(ToolPreconditionResult.Unsatisfied("the remote daemon is offline", suggestedFix = Some("start_daemon")))
  }

  /**
   * Consent-gated, destructive, detachable, preconditioned — the full
   * capability profile a proxy must forward by construction.
   */
  private case object GuardedWrappedTool extends Tool {
    type Input = FakeToolInput
    type Output = TextToolOutput
    val io: ToolIO[FakeToolInput, TextToolOutput] = ToolIO.derived[FakeToolInput, TextToolOutput]
    val spec: ToolSpec = ToolSpec(
      name = ToolName("guarded_fake_tool"),
      description = "Consent-gated destructive fake tool for proxy tests",
      profile = ToolProfile(
        effect = Effect.Destructive(MutationTargeting.none, "Permanently deletes the remote record."),
        gates = ToolGates(consent = Some(ConsentSpec("Really delete the remote record?"))),
        execution = Execution.Detachable(
          keepRunningOnStop = false,
          progress = ProgressContract("reports deletion progress via ctx.reportProgress")
        )
      ),
      discovery = DiscoverySpec(keywords = Set("test", "guarded"))
    )
    protected def resolve: Resolution[Input, Output] =
      Resolution.Simple((_, _) => Task.pure(TextToolOutput("local guarded run")))
  }

  private case object PreconditionedWrappedTool extends Tool {
    type Input = FakeToolInput
    type Output = TextToolOutput
    val io: ToolIO[FakeToolInput, TextToolOutput] = ToolIO.derived[FakeToolInput, TextToolOutput]
    val spec: ToolSpec = ToolSpec(
      name = ToolName("preconditioned_fake_tool"),
      description = "Fake tool with a permanently-unsatisfied precondition for proxy tests",
      profile = ToolProfile(
        effect = Effect.Mutating(MutationTargeting.none),
        gates = ToolGates(preconditions = List(OfflineDaemonPrecondition))
      ),
      discovery = DiscoverySpec(keywords = Set("test", "preconditioned"))
    )
    protected def resolve: Resolution[Input, Output] =
      Resolution.Simple((_, _) => Task.pure(TextToolOutput("local preconditioned run")))
  }

  "ProxyTool" should {
    "preserve the wrapped tool's spec, name, description, and schema" in rapid.Task {
      val transport = new RecordingTransport
      val proxy = new ProxyTool(FakeWrappedTool, transport)
      proxy.spec shouldBe FakeWrappedTool.spec
      proxy.name shouldBe FakeWrappedTool.name
      proxy.description shouldBe FakeWrappedTool.description
      proxy.schema.input shouldBe FakeWrappedTool.schema.input
      proxy.examples shouldBe FakeWrappedTool.examples
      proxy.modes shouldBe FakeWrappedTool.modes
      proxy.keywords shouldBe FakeWrappedTool.keywords
      proxy.kind shouldBe FakeWrappedTool.kind
    }

    "route execute through the transport with the input rendered to Json" in {
      val transport = new RecordingTransport
      val proxy = new ProxyTool(FakeWrappedTool, transport)
      val ctx = makeContext()
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
      val proxy = new ProxyTool(FakeWrappedTool, transport)
      val ctx = makeContext()
      transport.respondWith(ToolResult.Success(obj("text" -> str("ok"))))

      proxy.execute(FakeToolInput(value = 1), ctx, Event.id()).toList.map { _ =>
        val (capturedName, _, _) = transport.lastCall.get()
        capturedName shouldBe FakeWrappedTool.name
      }
    }

    "forward the full capability profile through the decorator — consent, destructive warning, preconditions, detachability" in rapid.Task {
      val proxy = new ProxyTool(GuardedWrappedTool, new RecordingTransport)
      proxy.requiresUserConsent shouldBe true
      proxy.consentPrompt shouldBe Some("Really delete the remote record?")
      proxy.destructive shouldBe true
      proxy.wireDescription(sigil.provider.ConversationMode, TestSigil) should
        startWith("**Permanently deletes the remote record.**")
      proxy.detachable shouldBe true
      proxy.detachedKeepRunningOnStop shouldBe false

      val preconditioned = new ProxyTool(PreconditionedWrappedTool, new RecordingTransport)
      preconditioned.preconditions shouldBe List(OfflineDaemonPrecondition)
    }

    "hold a proxied call at the consent gate — the transport never fires without a recorded approval" in {
      val transport = new RecordingTransport
      val proxy = new ProxyTool(GuardedWrappedTool, transport)
      val ctx = makeContext()
      proxy.execute(FakeToolInput(value = 3), ctx, Event.id()).toList.map { events =>
        withClue(s"transport dispatched despite the unconsented gate; events: $events: ") {
          transport.callCount.get() shouldBe 0
        }
        events should not be empty
      }
    }

    "hold a proxied call at an unsatisfied precondition — the transport never fires" in {
      val transport = new RecordingTransport
      val proxy = new ProxyTool(PreconditionedWrappedTool, transport)
      val ctx = makeContext()
      proxy.execute(FakeToolInput(value = 3), ctx, Event.id()).toList.map { events =>
        withClue(s"transport dispatched despite the unsatisfied precondition; events: $events: ") {
          transport.callCount.get() shouldBe 0
        }
        events should not be empty
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
      turnInput = sigil.conversation.TurnInput(sigil.conversation.ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
  }

  /**
   * Test transport — records each dispatch call, replays a configured result.
   */
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
