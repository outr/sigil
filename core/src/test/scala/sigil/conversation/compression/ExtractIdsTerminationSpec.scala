package sigil.conversation.compression

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spec.TestSigil

/**
 * Regression for sigil bug #141 — `extractIds` spun forever when
 * `Information[` appeared without a closing `]`. Bricked the curator
 * pipeline for any conversation containing one such frame.
 *
 * Unit-level rather than going through the full curator: the bug
 * fires inside one private helper, and the test would either return
 * in microseconds (fixed) or hang the suite forever (regressed). A
 * direct call surfaces both outcomes immediately.
 */
class ExtractIdsTerminationSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val curator = StandardContextCurator(TestSigil)

  "extractIds" should {

    "return immediately when the needle is unterminated (no closing ])" in {
      val ids = curator.extractIds("Look at Information[user-pinned but no closing here", "Information[").toList
      // No well-formed reference present — id set is empty. The
      // contract is "terminates"; emptiness is a sanity check the
      // bisect-test didn't trip on residual matches.
      ids shouldBe Nil
    }

    "extract every well-formed reference and skip an unterminated tail" in {
      val ids = curator.extractIds(
        "See Information[alpha-1] and Information[beta-2] and Information[runaway",
        "Information["
      ).toList
      ids should contain inOrder ("alpha-1", "beta-2")
      ids should not contain "runaway"
    }

    "extract back-to-back references when one immediately follows another" in {
      val ids = curator.extractIds("Information[one]Information[two]", "Information[").toList
      ids shouldBe List("one", "two")
    }

    "extract the inner-most id when a reference contains a nested `[` (no greedy backtrack)" in {
      // The framework's id encoding is `Information[<bare-id>]` —
      // `indexOf(']')` returns the FIRST `]` after `start`, which is
      // the semantically correct boundary. A `[` between needle and
      // `]` (junk content) is harmlessly captured as part of the id.
      val ids = curator.extractIds("Information[id-with-[brackets]-rest]", "Information[").toList
      ids shouldBe List("id-with-[brackets")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => org.scalatest.Assertions.succeed).sync()
  }
}
