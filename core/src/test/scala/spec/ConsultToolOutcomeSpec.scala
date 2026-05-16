package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.Model
import sigil.provider.{
  CallId, GenerationSettings, Provider, ProviderCall, ProviderEvent,
  ProviderType, ReasoningMode, StopReason, TokenUsage
}
import sigil.tool.consult.{ConsultOutcome, ConsultTool}
import sigil.tool.{ToolInput, ToolName, TypedTool}
import spice.http.HttpRequest

/**
 * Coverage for sigil bugs #196 + #197.
 *
 * #196 — `GenerationSettings.classifierDefault` (bounded
 * `maxOutputTokens` + `reasoningMode = Off`) is the default for
 * `ConsultTool.invoke` / `invokeRich`. Thinking-mode models can no
 * longer burn unbounded `reasoning_content` tokens on a categorical
 * decision; default is safe for the dominant ConsultTool use case.
 *
 * #197 — `invokeRich` returns a [[ConsultOutcome]] that distinguishes
 * `Parsed` / `NoOpinion` / `Truncated` / `Failed`. The legacy
 * `invoke` adapter still returns `Option[I]` but is now defined via
 * `invokeRich`, so the four-state distinction is available to
 * callers that opt into the richer shape.
 */
class ConsultToolOutcomeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "consult-outcome-model")

  case class ProbeInput(answer: String = "") extends ToolInput derives RW
  ToolInput.register(RW.static(ProbeInput()))

  private case object ProbeTool extends TypedTool[ProbeInput](
    name        = ToolName("consult_probe"),
    description = "Probe target for ConsultTool outcome tests."
  ) {
  override def paginate: Boolean = false

    override protected def executeTyped(input: ProbeInput, ctx: sigil.TurnContext): Stream[sigil.event.Event] =
      Stream.empty
  }

  /** Build a fake provider that yields the supplied event stream
    * for `call`. Captures the request's effective `generationSettings`
    * so the #196 default-application can be asserted. */
  private final class ScriptedProvider(events: List[ProviderEvent]) extends Provider {
    @volatile var lastSettings: Option[GenerationSettings] = None
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      lastSettings = Some(input.generationSettings)
      Stream.emits(events)
    }
  }

  private def withProvider[A](provider: Provider)(body: => Task[A]): Task[A] = {
    TestSigil.setProvider(Task.pure(provider))
    body
  }

  "sigil bug #196 — classifier-default generation settings" should {

    "apply bounded maxOutputTokens + reasoningMode.Off by default when caller omits generationSettings" in {
      val provider = new ScriptedProvider(List(
        ProviderEvent.ToolCallStart(CallId("p1"), ProbeTool.schema.name.value),
        ProviderEvent.ToolCallComplete(CallId("p1"), ProbeInput("ok")),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
      withProvider(provider) {
        ConsultTool.invoke[ProbeInput](
          sigil        = TestSigil,
          modelId      = modelId,
          chain        = List(TestUser),
          systemPrompt = "sys",
          userPrompt   = "ask",
          tool         = ProbeTool
          // generationSettings omitted — should default to classifierDefault.
        ).map { _ =>
          val settings = provider.lastSettings.get
          settings.maxOutputTokens shouldBe defined
          settings.maxOutputTokens.get should be > 0
          settings.reasoningMode shouldBe ReasoningMode.Off
        }
      }
    }

    "GenerationSettings.classifierDefault exposes the same shape" in {
      val s = GenerationSettings.classifierDefault
      s.maxOutputTokens shouldBe defined
      s.maxOutputTokens.get should be > 0
      s.reasoningMode shouldBe ReasoningMode.Off
      Task.pure(succeed)
    }
  }

  "sigil bug #197 — ConsultOutcome ADT distinguishes terminal states" should {

    "return Parsed when the model emits the expected tool_call" in {
      val provider = new ScriptedProvider(List(
        ProviderEvent.ToolCallStart(CallId("p1"), ProbeTool.schema.name.value),
        ProviderEvent.ToolCallComplete(CallId("p1"), ProbeInput("yes")),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
      withProvider(provider) {
        ConsultTool.invokeRich[ProbeInput](
          sigil        = TestSigil,
          modelId      = modelId,
          chain        = List(TestUser),
          systemPrompt = "sys",
          userPrompt   = "ask",
          tool         = ProbeTool
        ).map {
          case ConsultOutcome.Parsed(v) => v.answer shouldBe "yes"
          case other                    => fail(s"expected Parsed, got $other")
        }
      }
    }

    "return NoOpinion on a clean stream with no matching tool_call" in {
      val provider = new ScriptedProvider(List(
        ProviderEvent.Done(StopReason.Complete)
      ))
      withProvider(provider) {
        ConsultTool.invokeRich[ProbeInput](
          sigil        = TestSigil,
          modelId      = modelId,
          chain        = List(TestUser),
          systemPrompt = "sys",
          userPrompt   = "ask",
          tool         = ProbeTool
        ).map {
          case ConsultOutcome.NoOpinion => succeed
          case other                    => fail(s"expected NoOpinion, got $other")
        }
      }
    }

    "return Truncated when the stream closes with StopReason.MaxTokens and no tool_call" in {
      val provider = new ScriptedProvider(List(
        ProviderEvent.Usage(TokenUsage(promptTokens = 7500, completionTokens = 25000, totalTokens = 32500)),
        ProviderEvent.Done(StopReason.MaxTokens)
      ))
      withProvider(provider) {
        ConsultTool.invokeRich[ProbeInput](
          sigil        = TestSigil,
          modelId      = modelId,
          chain        = List(TestUser),
          systemPrompt = "sys",
          userPrompt   = "ask",
          tool         = ProbeTool
        ).map {
          case t: ConsultOutcome.Truncated =>
            t.promptTokens shouldBe Some(7500)
            t.completionTokens shouldBe Some(25000)
            t.totalTokens shouldBe Some(32500)
          case other =>
            fail(s"expected Truncated, got $other")
        }
      }
    }

    "legacy invoke returns None on every non-Parsed outcome (back-compat preserved)" in {
      val provider = new ScriptedProvider(List(
        ProviderEvent.Done(StopReason.MaxTokens)
      ))
      withProvider(provider) {
        ConsultTool.invoke[ProbeInput](
          sigil        = TestSigil,
          modelId      = modelId,
          chain        = List(TestUser),
          systemPrompt = "sys",
          userPrompt   = "ask",
          tool         = ProbeTool
        ).map {
          _ shouldBe None
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
