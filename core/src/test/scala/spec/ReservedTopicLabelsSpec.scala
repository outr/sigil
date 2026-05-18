package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{TopicEntry, TopicShiftResult}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.{
  CallId, GenerationSettings, OneShotRequest, Provider, ProviderCall,
  ProviderEvent, ProviderRequest, ProviderType, StopReason
}
import sigil.tool.consult.TopicClassifierInput
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #89 — generic / catch-all topic labels
 * (agent name, "Greeting", "Initial setup") in the prior list
 * pull every later turn back to them via the `<prior label>`
 * match path. The framework filters reserved labels out before
 * the classifier sees them.
 *
 * Verifies:
 *   1. Classifier never receives a reserved label as a prior
 *      candidate — the [[TopicClassifierTool]]'s schema only
 *      lists non-reserved priors.
 *   2. Classifier returning a label that was filtered out (e.g.
 *      because the model emits it anyway) → falls back to `New`,
 *      not `NoChange`.
 *   3. Apps add to `reservedTopicLabels` (e.g. agent display name)
 *      via override.
 */
class ReservedTopicLabelsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Provider that captures the request body and emits a scripted
   * topic-classifier response.
   */
  private class CapturingClassifierProvider(scriptedKind: String) extends Provider {
    val capturedSystemPrompt: AtomicReference[String] = new AtomicReference("")
    val capturedUserPrompt: AtomicReference[String] = new AtomicReference("")
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      capturedSystemPrompt.set(input.system)
      capturedUserPrompt.set(input.messages.headOption.map(_.toString).getOrElse(""))
      val callId = CallId("classifier-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "classify_topic_shift"),
        ProviderEvent.ToolCallComplete(callId, TopicClassifierInput(kind = scriptedKind)),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private val modelId: Id[Model] = Model.id("test", "topic-classifier")
  private val current = TopicEntry(
    id = sigil.conversation.Topic.id("topic-current"),
    label = "Workspace setup",
    summary = "Setting up the workspace."
  )

  private def reservedPrior(label: String): TopicEntry =
    TopicEntry(id = sigil.conversation.Topic.id(s"topic-$label"), label = label, summary = s"$label summary")

  "classifyTopicShift reserved-label filter (#89)" should {

    "exclude reserved-label priors from the classifier's input" in {
      val provider = new CapturingClassifierProvider("New")
      TestSigil.setProvider(Task.pure(provider))

      val priors = List(
        reservedPrior("Greeting"), // reserved
        reservedPrior("Initial setup"), // reserved
        reservedPrior("Compiler bug") // real prior
      )
      TestSigil.classifyTopicShift(
        modelId = modelId,
        chain = List(TestUser, TestAgent),
        current = current,
        priors = priors,
        proposedLabel = "Type-class derivation",
        proposedSummary = "Working on type-class derivation issues.",
        userMessage = "Let's look at the type-class derivation."
      ).map { _ =>
        val sysPrompt = provider.capturedSystemPrompt.get()
        val userPrompt = provider.capturedUserPrompt.get()
        sysPrompt should not be empty

        // The user prompt rendered with `priorsBlock` should NOT
        // include reserved labels.
        userPrompt should not include "Greeting"
        userPrompt should not include "Initial setup"
        // But genuine priors do flow through.
        userPrompt should include("Compiler bug")
      }
    }

    "fall back to New when the classifier returns a label that was filtered out" in {
      // Classifier returns "Greeting" — but Greeting was filtered
      // from the priors. Expect framework to settle on `New`,
      // not silently downgrade to `NoChange`.
      val provider = new CapturingClassifierProvider("Greeting")
      TestSigil.setProvider(Task.pure(provider))

      TestSigil.classifyTopicShift(
        modelId = modelId,
        chain = List(TestUser, TestAgent),
        current = current,
        priors = List(reservedPrior("Greeting"), reservedPrior("Compiler bug")),
        proposedLabel = "Brand new task",
        proposedSummary = "Something genuinely different.",
        userMessage = "Let's do something else."
      ).map { result =>
        // Greeting should never have been a candidate; the
        // unmatched label resolves to New.
        result shouldBe TopicShiftResult.New
      }
    }

    "force New when classifier returns a reserved label, even if a prior with that label survived (#92)" in {
      // Apps may add the agent's display name (e.g. "Sage") to
      // `reservedTopicLabels`. We override the registry directly
      // for the test to simulate that.
      val customReserved = TestSigil.reservedTopicLabels + "Sage"
      val originalReserved = TestSigil.reservedTopicLabels
      TestSigil.reservedTopicLabelsOverride.set(Some(customReserved))
      val provider = new CapturingClassifierProvider("Sage")
      TestSigil.setProvider(Task.pure(provider))

      val priors = List(reservedPrior("Compiler bug"))
      TestSigil.classifyTopicShift(
        modelId = modelId,
        chain = List(TestUser, TestAgent),
        current = current,
        priors = priors,
        proposedLabel = "Workspace setup refinement",
        proposedSummary = "Tightening up the workspace.",
        userMessage = "Hi Sage, can we refine the workspace?"
      ).map { result =>
        TestSigil.reservedTopicLabelsOverride.set(None)
        // Sage is a reserved label; classifier output forced to New.
        result shouldBe TopicShiftResult.New
        // The classifier prompt should also NOT contain the agent
        // name verbatim — sanitised on the way in.
        provider.capturedUserPrompt.get() should not include "Sage"
      }
    }

    "still match against non-reserved priors when classifier returns one of them" in {
      val provider = new CapturingClassifierProvider("Compiler bug")
      TestSigil.setProvider(Task.pure(provider))

      val realPrior = reservedPrior("Compiler bug")
      TestSigil.classifyTopicShift(
        modelId = modelId,
        chain = List(TestUser, TestAgent),
        current = current,
        priors = List(reservedPrior("Greeting"), realPrior),
        proposedLabel = "Compiler bug investigation",
        proposedSummary = "Continuing the compiler bug.",
        userMessage = "Let's keep digging into the compiler bug."
      ).map { result =>
        result shouldBe TopicShiftResult.Return(realPrior)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
