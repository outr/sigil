package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.signal.{
  DeleteViewerState, RequestViewerState, Signal, UpdateViewerState, ViewerStateSnapshot
}
import sigil.viewer.ViewerStatePayload

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Regression for bug #35 — typed per-viewer UI state primitive.
 * Verifies the full Notice triple round-trips a typed payload,
 * persistence survives across sessions, multi-session broadcast
 * reaches every subscriber for the viewer, and `Delete` clears
 * the row + broadcasts a `None` snapshot.
 */
class ViewerStateSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Register the test payload subtype with the framework's poly RW.
  // Apps in production register via `Sigil.viewerStatePayloadRegistrations`;
  // doing it manually here keeps the test self-contained.
  ViewerStatePayload.register(
    RW.static[ViewerStatePayload](TestViewerState(activeTab = "chat", panelOpen = false))
  )

  /** Subscribe `viewer` to its signal stream, capture into a queue. */
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

  private def snapshots(q: ConcurrentLinkedQueue[Signal], scope: String): List[ViewerStateSnapshot] = {
    import scala.jdk.CollectionConverters.*
    q.iterator().asScala.toList.collect {
      case s: ViewerStateSnapshot if s.scope == scope => s
    }
  }

  "RequestViewerState" should {
    "reply with payload=None when no record exists for the (viewer, scope) pair" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- TestSigil.handleNotice(RequestViewerState("ui-fresh"), TestUser)
        _ <- Task.sleep(100.millis)
      } yield {
        stop()
        val replies = snapshots(recorded, "ui-fresh")
        replies should have size 1
        replies.head.payload shouldBe None
      }
    }
  }

  "UpdateViewerState" should {
    "persist the typed payload AND broadcast a snapshot to the viewer's session" in {
      val (recorded, stop) = subscribe(TestUser)
      val payload = TestViewerState(activeTab = "files", panelOpen = true)
      for {
        _ <- TestSigil.handleNotice(UpdateViewerState("ui-update", payload), TestUser)
        _ <- Task.sleep(100.millis)
        // Now ask for it back — should round-trip the typed instance.
        _ <- TestSigil.handleNotice(RequestViewerState("ui-update"), TestUser)
        _ <- Task.sleep(100.millis)
      } yield {
        stop()
        val replies = snapshots(recorded, "ui-update")
        replies should have size 2
        // Both the broadcast-on-update AND the request reply should
        // carry the payload we wrote.
        replies.map(_.payload) shouldBe List(Some(payload), Some(payload))
        // Critical: the payload survives as a typed `TestViewerState`,
        // not as a raw Json or unbranded ViewerStatePayload.
        replies.head.payload.get shouldBe a[TestViewerState]
      }
    }
  }

  "UpdateViewerState broadcast" should {
    "reach every concurrent session subscribed to the same viewer" in {
      // Two parallel subscribers stand in for two browser tabs / devices.
      val (qA, stopA) = subscribe(TestUser)
      val (qB, stopB) = subscribe(TestUser)
      val payload = TestViewerState(activeTab = "settings", panelOpen = false)
      for {
        _ <- TestSigil.handleNotice(UpdateViewerState("ui-broadcast", payload), TestUser)
        _ <- Task.sleep(100.millis)
      } yield {
        stopA(); stopB()
        snapshots(qA, "ui-broadcast").map(_.payload) shouldBe List(Some(payload))
        snapshots(qB, "ui-broadcast").map(_.payload) shouldBe List(Some(payload))
      }
    }
  }

  "DeleteViewerState" should {
    "drop the row and broadcast a None snapshot" in {
      val (recorded, stop) = subscribe(TestUser)
      val payload = TestViewerState(activeTab = "tools", panelOpen = true)
      for {
        _ <- TestSigil.handleNotice(UpdateViewerState("ui-delete", payload), TestUser)
        _ <- Task.sleep(80.millis)
        _ <- TestSigil.handleNotice(DeleteViewerState("ui-delete"), TestUser)
        _ <- Task.sleep(80.millis)
        // Confirm the row is actually gone — request returns None.
        _ <- TestSigil.handleNotice(RequestViewerState("ui-delete"), TestUser)
        _ <- Task.sleep(80.millis)
      } yield {
        stop()
        val replies = snapshots(recorded, "ui-delete")
        // Sequence: update→Some(payload), delete→None, request→None.
        replies.map(_.payload) shouldBe List(Some(payload), None, None)
      }
    }
  }
}

/** Test-only payload — registered with the poly RW at spec init so
  * fabric can round-trip it through the wire shape. */
case class TestViewerState(activeTab: String, panelOpen: Boolean)
  extends ViewerStatePayload derives RW
