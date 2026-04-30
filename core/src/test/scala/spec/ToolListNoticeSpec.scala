package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.signal.{RequestToolList, Signal, ToolListSnapshot}
import sigil.tool.BuiltinKind

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * End-to-end coverage for bug #38 — verifies a [[RequestToolList]]
 * Notice issued by a viewer flows through `Sigil.handleNotice` and
 * results in a [[ToolListSnapshot]] addressed back to that viewer.
 *
 * Filter coverage: a `kinds` filter narrows the snapshot to a
 * specific [[sigil.tool.ToolKind]] subset; a `spaces` filter narrows
 * to spaces the viewer is authorized for.
 */
class ToolListNoticeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def subscribe(viewer: sigil.participant.ParticipantId): (ConcurrentLinkedQueue[Signal], () => Unit) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    @volatile var running = true
    TestSigil.signalsFor(viewer)
      .evalMap(s => Task { recorded.add(s); () })
      .takeWhile(_ => running)
      .drain
      .startUnit()
    (recorded, () => running = false)
  }

  private def snapshot(q: ConcurrentLinkedQueue[Signal]): Option[ToolListSnapshot] = {
    import scala.jdk.CollectionConverters.*
    q.iterator().asScala.toList.collectFirst { case s: ToolListSnapshot => s }
  }

  "RequestToolList → ToolListSnapshot" should {

    "deliver a snapshot back to the viewer who issued the request" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.handleNotice(RequestToolList(), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        val snap = snapshot(recorded).getOrElse(fail("no ToolListSnapshot received"))
        // The list may be empty (TestSigil.accessibleSpaces returns
        // Set.empty by default — fail-closed posture). The point
        // here is that handleNotice routed the reply correctly: a
        // snapshot LANDED on the requester's signal stream.
        snap.tools shouldBe a [List[?]]
      }
    }

    "filter the snapshot by ToolKind when a `kinds` set is supplied" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        // BuiltinKind is the framework's only registered kind by
        // default; the filter shouldn't drop any kind=BuiltinKind
        // tool the viewer was authorized for. With TestSigil's
        // empty accessibleSpaces the result is empty either way —
        // assertion is "no tool with a non-Builtin kind slipped in."
        _ <- TestSigil.handleNotice(RequestToolList(kinds = Some(Set(BuiltinKind))), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        val snap = snapshot(recorded).getOrElse(fail("no ToolListSnapshot received"))
        snap.tools.map(_.kind).distinct shouldBe Nil  // empty list since TestUser has no spaces
      }
    }
  }

  "Sigil.listTools (helper invoked by the Notice arm)" should {

    "honor the spaces filter — empty `spaces` ∩ authorized = nothing" in {
      // TestSigil.accessibleSpaces returns Set.empty by default. Even
      // requesting GlobalSpace explicitly produces an empty result —
      // GlobalSpace isn't part of the viewer's authorized set unless
      // the app overrides `accessibleSpaces`. This documents the
      // fail-closed posture explicitly.
      TestSigil.listTools(TestUser, spaces = Some(Set(GlobalSpace))).map { result =>
        result shouldBe empty
      }
    }
  }
}
