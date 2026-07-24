package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.signal.{
  Signal, UpdateViewerState, UpdateViewerStateDelta,
  ViewerStateDelta, ViewerStateSnapshot
}
import sigil.viewer.ViewerStatePayload

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Coverage for bug #43 — partial-update deltas + on-(re)connect
 * snapshot push for [[sigil.viewer.ViewerStatePayload]].
 *
 *   - `UpdateViewerStateDelta` deep-merges the patch onto the current
 *     payload via fabric's object merge; non-null fields in the
 *     patch overlay, untouched fields persist.
 *   - The merge result is broadcast as `ViewerStateDelta` to other
 *     sessions for the viewer.
 *   - First delta on a fresh scope acts like an upsert (no prior
 *     state to merge against).
 *   - `Sigil.publishViewerStatesTo(viewer)` fires a
 *     `ViewerStateSnapshot` for every scope the viewer owns —
 *     consumers call this from their auth-completion handler.
 */
class ViewerStateDeltaAndPushSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Test payload with Option fields — partial updates can leave
  // existing fields alone by setting their patch counterpart to None
  // (fabric serializes None as null, which the default merge config
  // overlays as null). For clean deep-merge behaviour the test sets
  // the same field to a new Some(_) and asserts the merged record.
  case class DeltaTestState(activeTab: Option[String] = None,
                            panelOpen: Option[Boolean] = None,
                            theme: Option[String] = None)
    extends ViewerStatePayload derives RW

  ViewerStatePayload.register(summon[RW[DeltaTestState]])

  private def subscribe(viewer: sigil.participant.ParticipantId): (ConcurrentLinkedQueue[Signal], () => Unit) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    @volatile var running = true
    // SignalHub registers the subscriber eagerly, so signals published
    // after this call are captured — no settle delay needed.
    TestSigil.signalsFor(viewer)
      .evalMap(s => Task { recorded.add(s); () })
      .takeWhile(_ => running)
      .drain
      .startUnit()
    (recorded, () => running = false)
  }

  private def collectDeltas(q: ConcurrentLinkedQueue[Signal], scope: String): List[ViewerStateDelta] =
    q.iterator().asScala.toList.collect { case d: ViewerStateDelta if d.scope == scope => d }

  private def collectSnapshots(q: ConcurrentLinkedQueue[Signal], scope: String): List[ViewerStateSnapshot] =
    q.iterator().asScala.toList.collect { case s: ViewerStateSnapshot if s.scope == scope => s }

  /**
   * Poll until `get` satisfies `done` (or the timeout elapses).
   * Replaces fixed sleeps for async Notice propagation.
   */
  private def await[A](timeout: FiniteDuration = 5.seconds)(get: => List[A])(done: List[A] => Boolean): Task[List[A]] = {
    def loop(remainingMs: Long): Task[List[A]] = {
      val cur = get
      if (done(cur) || remainingMs <= 0) Task.pure(cur)
      else Task.sleep(20.millis).flatMap(_ => loop(remainingMs - 20))
    }
    loop(timeout.toMillis)
  }

  "UpdateViewerStateDelta on a fresh scope" should {
    "act like an upsert — persist the patch as initial state and broadcast a delta" in {
      val (recorded, stop) = subscribe(TestUser)
      val patch = DeltaTestState(activeTab = Some("chat"))
      for {
        _ <- TestSigil.handleNotice(UpdateViewerStateDelta("delta-fresh", patch), TestUser)
        deltas <- await()(collectDeltas(recorded, "delta-fresh"))(_.nonEmpty)
      } yield {
        stop()
        deltas should have size 1
        deltas.head.patch shouldBe patch
      }
    }
  }

  "UpdateViewerStateDelta against existing state" should {
    "deep-merge the patch into the persisted payload (untouched fields preserved)" in {
      val (recorded, stop) = subscribe(TestUser)
      val initial = DeltaTestState(activeTab = Some("chat"), panelOpen = Some(true), theme = Some("light"))
      val patch = DeltaTestState(theme = Some("dark")) // only theme changes
      for {
        _ <- TestSigil.handleNotice(UpdateViewerState("delta-merge", initial), TestUser)
        _ <- await()(collectSnapshots(recorded, "delta-merge"))(_.nonEmpty)
        _ <- TestSigil.handleNotice(UpdateViewerStateDelta("delta-merge", patch), TestUser)
        _ <- await()(collectDeltas(recorded, "delta-merge"))(_.nonEmpty)
        // Snapshot the persisted state by issuing a Request — it
        // should reflect the merged result, not just the patch.
        _ <- TestSigil.handleNotice(sigil.signal.RequestViewerState("delta-merge"), TestUser)
        _ <- await()(collectSnapshots(recorded, "delta-merge"))(_.size >= 2)
      } yield {
        stop()
        // Deltas: just one (the patch broadcast).
        val deltas = collectDeltas(recorded, "delta-merge")
        deltas should have size 1
        deltas.head.patch shouldBe patch

        // Snapshots: initial upsert broadcast + reply to the request.
        // Both reflect persisted-after-merge state, not the bare patch.
        val snapshots = collectSnapshots(recorded, "delta-merge")
        snapshots should not be empty
        val merged = snapshots.last.payload.getOrElse(fail("expected non-empty payload after merge")).asInstanceOf[DeltaTestState]
        // theme overwritten...
        merged.theme shouldBe Some("dark")
        // ...activeTab + panelOpen preserved from before the patch.
        merged.activeTab shouldBe Some("chat")
        merged.panelOpen shouldBe Some(true)
      }
    }
  }

  "Sigil.publishViewerStatesTo" should {
    "fire a ViewerStateSnapshot for every persisted scope the viewer owns" in {
      val (recorded, stop) = subscribe(TestUser)
      val s1 = DeltaTestState(activeTab = Some("a"))
      val s2 = DeltaTestState(activeTab = Some("b"))
      for {
        // Seed two scopes for the viewer.
        _ <- TestSigil.handleNotice(UpdateViewerState("push-scope-1", s1), TestUser)
        _ <- TestSigil.handleNotice(UpdateViewerState("push-scope-2", s2), TestUser)
        // Wait for both seed broadcasts to land, then drain the queue
        // so we assert only on the snapshots the push generates.
        _ <- await()(collectSnapshots(recorded, "push-scope-1"))(_.nonEmpty)
        _ <- await()(collectSnapshots(recorded, "push-scope-2"))(_.nonEmpty)
        _ = recorded.clear()
        _ <- TestSigil.publishViewerStatesTo(TestUser)
        pushed <- await() {
          recorded.iterator().asScala.toList.collect {
            case s: ViewerStateSnapshot if s.scope.startsWith("push-scope-") => s.scope
          }
        }(scopes => scopes.contains("push-scope-1") && scopes.contains("push-scope-2"))
      } yield {
        stop()
        pushed.toSet should contain("push-scope-1")
        pushed.toSet should contain("push-scope-2")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
