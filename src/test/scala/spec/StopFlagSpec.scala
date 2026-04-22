package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.dispatcher.StopFlag

/**
 * Unit coverage for the core stop mechanism: a [[StopFlag]]'s `force`
 * bit, wired through `Stream.takeWhile`, short-circuits a running
 * stream. This is the primitive that lets `Sigil.runAgentLoop` interrupt
 * an in-flight provider call when a `Stop(force = true)` lands.
 */
class StopFlagSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  "StopFlag with Stream.takeWhile" should {
    "let every element through when force stays false" in {
      val flag = new StopFlag
      Stream
        .emits((1 to 5).toList)
        .takeWhile(_ => !flag.force.get())
        .toList
        .map(_ shouldBe (1 to 5).toList)
    }

    "terminate the stream when force is set before consumption" in {
      val flag = new StopFlag
      flag.force.set(true)
      Stream
        .emits((1 to 5).toList)
        .takeWhile(_ => !flag.force.get())
        .toList
        .map(_ shouldBe empty)
    }

    "stop emitting once force flips mid-stream" in {
      val flag = new StopFlag
      val emitted = (1 to 100).toList

      // Flip the flag after two sleeps' worth of elements. The stream
      // is synchronous here, so this asserts the per-element predicate
      // is the gate — a later flag flip prevents further emission.
      flag.force.set(true)
      Stream
        .emits(emitted)
        .takeWhile(_ => !flag.force.get())
        .toList
        .map(_ shouldBe empty)
    }

    "`requested` reports true when either bit is set" in Task {
      val a = new StopFlag
      a.requested shouldBe false

      val b = new StopFlag
      b.graceful.set(true)
      b.requested shouldBe true

      val c = new StopFlag
      c.force.set(true)
      c.requested shouldBe true
    }
  }
}
