package spec

import fabric.*
import fabric.io.{Format, JsonParser}
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
 * Coverage for the forensic-logging + diagnostic-fallback-marker
 * portion of the dangling-tool_call workaround in
 * [[sigil.provider.Provider.renderFrames]].
 *
 * When the corruption-resistance invariant is violated and a
 * `ContextFrame.ToolCall` reaches the renderer without a paired
 * `ContextFrame.ToolResult`, the renderer must
 *
 *   1. log a forensic dump of the in-trail invoke/result wireIds and
 *      settled/active counts, so the next field occurrence carries
 *      enough state to classify which path lost the result;
 *   2. emit a structured `function_call_output` payload (rather than
 *      a bare empty string) so the agent's next iteration reads an
 *      explicit "result unknown, do not blindly retry" marker
 *      instead of "the tool returned nothing."
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

  "Provider.renderFrames orphan-call fallback" should {

    "log forensic trail state when a tool_call has no paired ToolResult" in {
      val (_, logged) = captureRootLog {
        TestProvider.render(orphanFrames, agent)
      }
      val danglingError = logged.find(_.contains("dangling tool_call"))
      danglingError shouldBe defined
      val text = danglingError.get
      text should include ("invokes seen:")
      text should include ("results seen:")
      text should include ("invokes settled:")
      text should include ("invokes active:")
      // The orphan wireId is the framework `Id[Event]` (no upstream
      // wireCallId on the ContextFrame.ToolCall) — confirm the
      // forensic dump carries it and that the paired wireId shows
      // up in `results seen`.
      text should include (orphanCallId.value)
      text should include (pairedCallId.value)
    }

    "emit a structured diagnostic function_call_output marker for the orphan" in {
      val rendered = TestProvider.render(orphanFrames, agent)
      val orphanOutput = rendered.collectFirst {
        case t: ProviderMessage.ToolResult if t.toolCallId == orphanCallId.value => t
      }
      orphanOutput shouldBe defined
      val payloadJson = JsonParser(orphanOutput.get.content, Format.Json)
      payloadJson("_sigil_orphan_marker") shouldBe bool(true)
      payloadJson("_sigil_orphan_wireId") shouldBe str(orphanCallId.value)
      val message = payloadJson("_sigil_message").asString
      message should include (orphanCallId.value)
      message.toLowerCase should include ("do not retry")
      message.toLowerCase should include ("side-effect")
      // Paired call's result must still carry its real content,
      // not the orphan marker — the fallback only fires for the
      // unpaired call.
      val pairedOutput = rendered.collectFirst {
        case t: ProviderMessage.ToolResult if t.toolCallId == pairedCallId.value => t
      }
      pairedOutput.map(_.content) shouldBe Some("real-paired-result")
    }
  }
}
