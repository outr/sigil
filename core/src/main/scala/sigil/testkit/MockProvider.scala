package sigil.testkit

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import sigil.provider.*
import sigil.tool.ToolInput
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, CopyOnWriteArrayList}
import scala.jdk.CollectionConverters.*

/**
 * Scriptable [[Provider]] for tests. Lets specs enqueue a sequence of
 * canned responses and inspect the [[ProviderCall]]s the framework
 * produced. Replaces the scalagentic-era `MockModel` factory.
 *
 * Typical usage:
 * {{{
 *   val mock = MockProvider(
 *     sigil = TestSigil,
 *     responses = List(
 *       MockProvider.Script.toolCall(CallId("c1"), "respond", RespondInput("Hi!")),
 *       MockProvider.Script.text("Done.")
 *     )
 *   )
 *   TestSigil.setProvider(Task.pure(mock))
 *   // ... drive a turn ...
 *   mock.calls should have size 1
 * }}}
 *
 * The provider tracks every [[ProviderCall]] it receives. Tests assert
 * on `calls` for wire-shape verification (system prompt body, message
 * sequence, tool list) without spinning up an HTTP server.
 *
 * `httpRequestFor` raises — MockProvider has no wire format. Specs that
 * need to assert the rendered HTTP payload should target a real provider
 * with the wire interceptor instead.
 */
final class MockProvider(
  override protected val sigil: Sigil,
  responses: List[MockProvider.Script] = Nil,
  defaultResponse: MockProvider.Script = MockProvider.Script.text("(mock default)"),
  modelIdValue: String = "mock/mock-model",
  providerTypeValue: ProviderType = ProviderType.LlamaCpp
) extends Provider {

  override def `type`: ProviderType = providerTypeValue
  override def providerKey: String = "mock"

  private val queue = new ConcurrentLinkedQueue[MockProvider.Script](responses.asJava)
  private val captured = new CopyOnWriteArrayList[ProviderCall]()

  /** All [[ProviderCall]]s observed by `call`, in order received. */
  def calls: List[ProviderCall] = captured.asScala.toList

  /** Most recent [[ProviderCall]] (or None if `call` hasn't been invoked). */
  def lastCall: Option[ProviderCall] =
    if (captured.isEmpty) None else Some(captured.get(captured.size() - 1))

  /** Append more scripted responses; drained in FIFO order. */
  def enqueue(scripts: MockProvider.Script*): Unit = scripts.foreach(queue.add)

  /** Drop any not-yet-consumed scripts and clear captured calls. */
  def reset(): Unit = {
    queue.clear()
    captured.clear()
  }

  /** Synthetic single model so `models` returns something usable when
    * specs query catalogues. The real id resolves through `sigil.cache`
    * just like any other provider, but local mock-only specs typically
    * skip the registry. */
  override def models: List[Model] = Nil

  override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
    captured.add(input)
    val script = Option(queue.poll()).getOrElse(defaultResponse)
    Stream.emits(script.events)
  }

  override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    Task.error(new UnsupportedOperationException(
      "MockProvider has no wire format; use a real provider for HTTP-payload assertions."
    ))
}

object MockProvider {

  /**
   * A scripted streaming response: a sequence of [[ProviderEvent]]s
   * the [[MockProvider]] emits the next time `call` is invoked. The
   * sequence MUST end with a terminator ([[ProviderEvent.Done]] or
   * [[ProviderEvent.Error]]); the helpers in [[Script]]'s companion
   * provide that terminator automatically.
   */
  final case class Script(events: List[ProviderEvent])

  object Script {

    /** Plain text response: a single TextDelta + Done(Complete). */
    def text(content: String,
             stopReason: StopReason = StopReason.Complete): Script =
      Script(List(
        ProviderEvent.TextDelta(content),
        ProviderEvent.Done(stopReason)
      ))

    /** Streamed text in chunks: each segment becomes a separate
      * TextDelta, terminated with Done. */
    def stream(segments: String*)(implicit dummyImplicit: DummyImplicit): Script =
      Script(segments.map(ProviderEvent.TextDelta.apply).toList :+ ProviderEvent.Done(StopReason.Complete))

    /** Tool-call response: a ToolCallStart + ToolCallComplete + Done(ToolCall). */
    def toolCall(callId: CallId,
                 toolName: String,
                 input: ToolInput,
                 stopReason: StopReason = StopReason.ToolCall): Script =
      Script(List(
        ProviderEvent.ToolCallStart(callId, toolName),
        ProviderEvent.ToolCallComplete(callId, input),
        ProviderEvent.Done(stopReason)
      ))

    /** Error termination — no content, just an Error event. */
    def error(message: String): Script =
      Script(List(ProviderEvent.Error(message)))

    /** Custom event sequence — caller supplies every event including
      * the terminator. Use this when the helpers above don't cover the
      * shape under test (multi-tool calls, image generation, content
      * blocks, mixed thinking + text, etc.). */
    def custom(events: ProviderEvent*): Script = Script(events.toList)
  }

  /** Convenience factory: returns a [[MockProvider]] wired against the
    * given [[Sigil]] with an initial script list. */
  def apply(sigil: Sigil,
            responses: List[Script] = Nil,
            defaultResponse: Script = Script.text("(mock default)"),
            modelIdValue: String = "mock/mock-model"): MockProvider =
    new MockProvider(sigil, responses, defaultResponse, modelIdValue)
}
