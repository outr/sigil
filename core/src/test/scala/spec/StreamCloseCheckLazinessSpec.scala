package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream}
import sigil.provider.wire.OpenAIChatCompletions

import scala.collection.mutable.ListBuffer

/**
 * The streaming connection-close check (`StreamState.closeStream`) must run
 * only AFTER the line stream drains, never before. `Stream.++` resolves the
 * right operand's `task` eagerly, so an `events ++ Stream.force(Task(check))`
 * shape evaluates the check at stream-setup — against an empty state — and
 * reports every slow-first-byte provider (e.g. Cloudflare/Kimi, multi-second
 * time-to-first-byte) as a truncated stream. [[OpenAIChatCompletions.appendTerminal]]
 * defers the terminal to pull time so it observes the real end-of-stream state.
 */
class StreamCloseCheckLazinessSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  "OpenAIChatCompletions.appendTerminal" should {

    "evaluate the terminal only after the source stream has fully drained" in {
      val order = ListBuffer.empty[String]
      val src = Stream.emits(List("a", "b", "c")).map { x =>
        order.synchronized(order += s"src:$x")
        x
      }
      val combined = OpenAIChatCompletions.appendTerminal(src) {
        order.synchronized(order += "terminal")
        List("end")
      }
      combined.toList.map { result =>
        result shouldBe List("a", "b", "c", "end")
        // The terminal must come LAST — the eager `++` shape put it first.
        order.toList shouldBe List("src:a", "src:b", "src:c", "terminal")
      }
    }

    "not evaluate the terminal at all until the combined stream is consumed" in {
      @volatile var evaluated = false
      val combined = OpenAIChatCompletions.appendTerminal(Stream.emits(List(1, 2))) {
        evaluated = true
        List(3)
      }
      // Holding the Stream value must not have run the thunk.
      evaluated shouldBe false
      combined.toList.map { result =>
        result shouldBe List(1, 2, 3)
        evaluated shouldBe true
      }
    }

    "propagate a terminal that throws (the truncation signal) after the source drains" in {
      val order = ListBuffer.empty[String]
      val src = Stream.emits(List("x")).map { v => order.synchronized(order += "src"); v }
      val combined = OpenAIChatCompletions.appendTerminal[String](src) {
        order.synchronized(order += "terminal")
        throw new RuntimeException("boom")
      }
      combined.toList.attempt.map { result =>
        result.isFailure shouldBe true
        // Source still drained before the terminal threw.
        order.toList shouldBe List("src", "terminal")
      }
    }
  }
}
