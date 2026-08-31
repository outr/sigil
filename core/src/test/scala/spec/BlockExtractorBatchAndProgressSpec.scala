package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.ContextFrame
import sigil.conversation.compression.{BlockExtractor, StandardBlockExtractor}
import sigil.event.{Event, MessageVisibility}
import sigil.information.{Information, StoredInformation}

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/**
 * Regression for sigil bug #142 — `StandardBlockExtractor` used to
 * issue one `putInformation` call per qualifying frame. Bulk imports
 * paid N transaction-commits / Lucene-segment-flushes; minutes-long
 * opaque step on a 50K-event import.
 *
 * The fix:
 *   - new `Sigil.putInformations(Vector[Information])` bulk hook;
 *     the framework default bulk-writes `StoredInformation` records
 *     in one transaction, apps with a custom catalog override.
 *   - `StandardBlockExtractor` collects every record in one pass,
 *     then calls `putInformations` exactly once.
 *   - new `progress: BlockExtractor.ProgressCallback` argument
 *     fires every `progressEvery` frames so the activity bar
 *     reflects forward motion.
 */
class BlockExtractorBatchAndProgressSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Minimal Information subtype for the spec.
   */
  case class BlockInfo(id: Id[Information], content: String) extends Information derives RW

  private val longText: String = "A" * 3000 // exceeds default 2000-char threshold

  private def buildFrames(count: Int): Vector[ContextFrame] = (0 until count).toVector.map { i =>
    ContextFrame.Text(
      content = s"$longText (frame $i)",
      participantId = TestUser,
      sourceEventId = Id[Event](s"e-$i"),
      visibility = MessageVisibility.All
    )
  }

  private val extractor = StandardBlockExtractor(
    toInformation = (content, id) => BlockInfo(id, content),
    minChars = 2000,
    progressEvery = 4
  )

  "StandardBlockExtractor" should {

    "call putInformations exactly once for the whole batch when the host overrides it" in {
      TestSigil.reset()
      val batches = new AtomicReference[Vector[Vector[Information]]](Vector.empty)
      val perRecord = new AtomicInteger(0)
      TestSigil.onPutInformation(_ => perRecord.incrementAndGet())
      TestSigil.onPutInformations(batch => batches.updateAndGet(_ :+ batch))

      extractor.extract(TestSigil, buildFrames(10)).map { result =>
        result.frames should have size 10
        result.information should have size 10
        val captured = batches.get()
        captured should have size 1
        captured.head should have size 10
        // The per-record hook stays unused — the host's bulk
        // override absorbed the writes.
        perRecord.get() shouldBe 0
      }
    }

    "persist the batch via the framework-default putInformations when the host does NOT override it" in {
      // No `onPutInformations` override — the framework default
      // bulk-writes StoredInformation records to SigilDB.storedInformations.
      TestSigil.reset()
      val storedExtractor = StandardBlockExtractor(
        toInformation = (content, id) => StoredInformation.fromInformationId(id, content),
        minChars = 2000,
        progressEvery = 4
      )
      storedExtractor.extract(TestSigil, buildFrames(5)).flatMap { result =>
        result.information should have size 5
        Task.sequence(result.information.toList.map { summary =>
          TestSigil.withDB(_.storedInformations.transaction(_.get(Id[StoredInformation](summary.id.value))))
        }).map { fetched =>
          fetched.flatten should have size 5
        }
      }
    }

    "emit progress callbacks at the configured cadence + a final pulse" in {
      TestSigil.reset()
      val pulses = new AtomicInteger(0)
      val cb: BlockExtractor.ProgressCallback = (_, _) => Task { pulses.incrementAndGet(); () }
      extractor.extract(TestSigil, buildFrames(10), cb).map { _ =>
        // progressEvery = 4 → callback fires at 4 + 8 + final
        // mop-up (10 % 4 != 0 so the trailing pulse fires).
        pulses.get() shouldBe 3
      }
    }

    "skip the final pulse when total is an exact multiple of progressEvery (no double-fire)" in {
      TestSigil.reset()
      val pulses = new AtomicInteger(0)
      val cb: BlockExtractor.ProgressCallback = (_, _) => Task { pulses.incrementAndGet(); () }
      extractor.extract(TestSigil, buildFrames(8), cb).map { _ =>
        pulses.get() shouldBe 2 // 4 and 8, no trailing
      }
    }

    "leave short frames unmodified and not invoke the bulk hook for an empty batch" in {
      TestSigil.reset()
      val batches = new AtomicInteger(0)
      TestSigil.onPutInformations(_ => batches.incrementAndGet())

      val short = ContextFrame.Text(
        content = "short",
        participantId = TestUser,
        sourceEventId = Id[Event]("e-short"),
        visibility = MessageVisibility.All
      )
      extractor.extract(TestSigil, Vector(short)).map { result =>
        result.frames shouldBe Vector(short)
        result.information shouldBe empty
        // Empty batch → no bulk-write fires.
        batches.get() shouldBe 0
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
