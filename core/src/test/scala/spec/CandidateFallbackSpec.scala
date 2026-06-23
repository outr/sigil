package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.CandidateFallback
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.provider.{ErrorClassifier, ProviderStreamException, RequestOverBudgetException}
import sigil.signal.{EventState, Signal}
import sigil.tool.model.ResponseContent

import java.util.concurrent.atomic.AtomicReference

/**
 * Sigil #397 Gap B — the live turn path must fall back to the next routed
 * candidate when the chosen one fails (pre-commit, non-Fatal) instead of
 * killing the turn. Drives [[CandidateFallback.stream]] directly with stub
 * per-candidate streams: the agent-loop wiring above it is unchanged, the
 * load-bearing logic is here.
 */
class CandidateFallbackSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private val convId = Conversation.id("cand-fallback")
  private val opus = Model.id("anthropic", "opus")
  private val sonnet = Model.id("anthropic", "sonnet")

  private def msg(text: String): Signal =
    Message(participantId = TestUser, conversationId = convId, topicId = TestTopicId,
      content = Vector(ResponseContent.Text(text)), state = EventState.Complete)

  private def overload: Stream[Signal] =
    Stream.emit(()).evalMap[Signal](_ => Task.error[Signal](
      new ProviderStreamException(providerKey = "anthropic", code = 0, typ = "overloaded_error",
        message_ = "Overloaded", status = None)))

  private def succeeds(text: String): Stream[Signal] =
    Stream.emits(List(msg(text)))

  "CandidateFallback.stream (#397 Gap B)" should {

    "fall back to the next candidate when the first overloads at stream start" in {
      val reported = new AtomicReference[List[Id[Model]]](Nil)
      val attempts = new AtomicReference[List[Id[Model]]](Nil)
      val stream = CandidateFallback.stream(
        candidates    = List(opus, sonnet),
        classifier    = ErrorClassifier.Default,
        reportFailure = id => reported.updateAndGet(_ :+ id)
      ) { id =>
        attempts.updateAndGet(_ :+ id)
        if (id == opus) overload else succeeds("answered by sonnet")
      }
      stream.toList.map { signals =>
        // The turn completed on the SECOND candidate, no error surfaced.
        signals.collect { case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString }
          .mkString should include("answered by sonnet")
        attempts.get() shouldBe List(opus, sonnet)
        // The failed candidate's cooldown was primed.
        reported.get() shouldBe List(opus)
      }
    }

    "NOT fall back once the first candidate has emitted observable output" in {
      val reported = new AtomicReference[List[Id[Model]]](Nil)
      val attempts = new AtomicReference[List[Id[Model]]](Nil)
      // First candidate emits a message THEN errors mid-stream — re-running on
      // another model would duplicate the already-streamed output.
      val partialThenError: Stream[Signal] =
        Stream.emits[Signal](List(msg("partial"))) ++ overload
      val stream = CandidateFallback.stream(
        candidates    = List(opus, sonnet),
        classifier    = ErrorClassifier.Default,
        reportFailure = id => reported.updateAndGet(_ :+ id)
      ) { id =>
        attempts.updateAndGet(_ :+ id)
        if (id == opus) partialThenError else succeeds("should never run")
      }
      stream.toList.attempt.map { result =>
        result.isFailure shouldBe true
        attempts.get() shouldBe List(opus)        // sonnet never attempted
        reported.get() shouldBe Nil               // no cooldown — committed output
      }
    }

    "surface a Fatal error immediately without trying the next candidate" in {
      val reported = new AtomicReference[List[Id[Model]]](Nil)
      val attempts = new AtomicReference[List[Id[Model]]](Nil)
      val fatal: Stream[Signal] = Stream.emit(()).evalMap[Signal](_ => Task.error[Signal](
        new RequestOverBudgetException(estimatedTokens = 99999, contextLength = 8192, modelId = opus)))
      val stream = CandidateFallback.stream(
        candidates    = List(opus, sonnet),
        classifier    = ErrorClassifier.Default,
        reportFailure = id => reported.updateAndGet(_ :+ id)
      ) { id =>
        attempts.updateAndGet(_ :+ id)
        if (id == opus) fatal else succeeds("should never run")
      }
      stream.toList.attempt.map { result =>
        result.isFailure shouldBe true
        attempts.get() shouldBe List(opus)        // Fatal → no fallback
        reported.get() shouldBe Nil
      }
    }

    "exhaust the chain and surface the last error when every candidate fails" in {
      val reported = new AtomicReference[List[Id[Model]]](Nil)
      val stream = CandidateFallback.stream(
        candidates    = List(opus, sonnet),
        classifier    = ErrorClassifier.Default,
        reportFailure = id => reported.updateAndGet(_ :+ id)
      )(_ => overload)
      stream.toList.attempt.map { result =>
        result.isFailure shouldBe true
        // Only the non-final candidate is cooled down (the last just surfaces).
        reported.get() shouldBe List(opus)
      }
    }
  }
}
