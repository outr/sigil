package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Stream
import scribe.Level
import scribe.handler.LogHandler
import scribe.writer.CacheWriter
import sigil.conversation.ContextFrame
import sigil.event.Event
import sigil.provider.{Provider, ProviderCall, ProviderEvent, ProviderMessage, ProviderType}
import sigil.tool.ToolName

/**
 * Coverage for [[sigil.provider.Provider.renderFrames]]'s handling of
 * a dangling `ContextFrame.ToolCall`.
 *
 * Under the typed tool-execution model every tool call is paired with
 * a result event by construction, so a dangling `ToolCall` reaching
 * the renderer is a genuine framework bug. The renderer must
 *
 *   1. log it loudly (a `dangling tool_call` error naming the wireId)
 *      rather than papering over it;
 *   2. NOT fabricate a synthetic `function_call_output` — the
 *      wire-side orphan-heal is removed, so a real bug surfaces
 *      instead of being masked.
 *
 * Forcing the orphan path here is the same shape every renderFrames
 * spec uses: a `ContextFrame.ToolCall` with no following
 * `ContextFrame.ToolResult` in the trail.
 */
class OrphanToolInvokeSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private object TestProvider extends Provider {
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def `type`: ProviderType = ProviderType.OpenAI
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.empty
    override def httpRequestFor(input: ProviderCall): rapid.Task[spice.http.HttpRequest] =
      rapid.Task.error(new RuntimeException("not implemented"))
    def render(frames: Vector[ContextFrame], agentId: TestAgent.type): Vector[ProviderMessage] =
      renderFrames(frames, Some(agentId))
  }

  private val agent = TestAgent
  private val nonAtomicName = ToolName("vector_lookup")

  private val orphanCallId: Id[Event] = Id[Event]("call-orphan")
  private val pairedCallId: Id[Event] = Id[Event]("call-paired")

  /** Force an orphan render path: one paired call (so the trail
    * carries a real `results seen` entry) and one unpaired call
    * (the renderer's fallback fires for it). */
  private def orphanFrames: Vector[ContextFrame] = Vector(
    ContextFrame.ToolCall(
      toolName = nonAtomicName,
      argsJson = """{"q":"paired"}""",
      callId = pairedCallId,
      participantId = agent,
      sourceEventId = Id[Event]("frame-paired-call")
    ),
    ContextFrame.ToolResult(
      callId = pairedCallId,
      content = "real-paired-result",
      sourceEventId = Id[Event]("frame-paired-result")
    ),
    ContextFrame.ToolCall(
      toolName = nonAtomicName,
      argsJson = """{"q":"orphan"}""",
      callId = orphanCallId,
      participantId = agent,
      sourceEventId = Id[Event]("frame-orphan-call")
    )
  )

  /** Install a [[CacheWriter]] on the root scribe logger for the
    * scope of `body`, capture the emitted error-level messages,
    * and restore the root logger on exit. */
  private def captureRootLog[A](body: => A): (A, List[String]) = {
    val previousRoot = scribe.Logger.root
    val cache = new CacheWriter()
    val handler = LogHandler(writer = cache, minimumLevel = Some(Level.Error))
    scribe.Logger.root.clearHandlers().withHandler(handler).replace()
    try {
      val result = body
      val messages = cache.consumeMessages(identity)
      (result, messages)
    } finally {
      scribe.Logger.replace(previousRoot)
    }
  }

  "Provider.renderFrames with a dangling tool_call" should {

    "log the framework-bug error naming the dangling wireId" in {
      val (_, logged) = captureRootLog {
        TestProvider.render(orphanFrames, agent)
      }
      val danglingError = logged.find(_.contains("dangling tool_call"))
      danglingError shouldBe defined
      val text = danglingError.get
      text should include ("framework bug")
      text should include ("invokes seen:")
      text should include ("results seen:")
      // The orphan wireId and the paired wireId both show up in the
      // forensic dump.
      text should include (orphanCallId.value)
      text should include (pairedCallId.value)
    }

    "synthesize NO function_call_output for the orphan — the wire-side heal is removed" in {
      val rendered = TestProvider.render(orphanFrames, agent)
      // No fabricated result for the unpaired call.
      rendered.collectFirst {
        case t: ProviderMessage.ToolResult if t.toolCallId == orphanCallId.value => t
      } shouldBe None
      // The paired call still carries its real result content — the
      // dangling-call handling doesn't disturb well-formed calls.
      val pairedOutput = rendered.collectFirst {
        case t: ProviderMessage.ToolResult if t.toolCallId == pairedCallId.value => t
      }
      pairedOutput.map(_.content) shouldBe Some("real-paired-result")
    }
  }
}
