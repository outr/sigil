package spec

import fabric.define.DefType
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.signal.{DeleteViewerState, RequestViewerState, Signal, UpdateViewerState, ViewerStateSnapshot}
import sigil.viewer.ViewerStatePayload

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

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
  // doing it manually here keeps the test self-contained. Critically:
  // we register the macro-derived `RW[TestViewerState]` (NOT
  // `RW.static(instance)`) so the field schema survives — that's the
  // shape `viewerStatePayloadRegistrations` expects per #36's fix.
  ViewerStatePayload.register(summon[RW[TestViewerState]])

  /**
   * Subscribe `viewer` to its signal stream, capture into a queue.
   */
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

  private def snapshots(q: ConcurrentLinkedQueue[Signal], scope: String): List[ViewerStateSnapshot] =
    q.iterator().asScala.toList.collect {
      case s: ViewerStateSnapshot if s.scope == scope => s
    }

  /**
   * Poll until at least `count` snapshots for `scope` have landed (or
   * the timeout elapses). Replaces fixed sleeps for async Notice
   * propagation — and, when called between actions, preserves the
   * arrival ordering the sequential sleeps used to enforce.
   */
  private def awaitSnapshots(q: ConcurrentLinkedQueue[Signal],
                             scope: String,
                             count: Int,
                             timeout: FiniteDuration = 5.seconds): Task[List[ViewerStateSnapshot]] = {
    def loop(remainingMs: Long): Task[List[ViewerStateSnapshot]] = {
      val snap = snapshots(q, scope)
      if (snap.size >= count || remainingMs <= 0) Task.pure(snap)
      else Task.sleep(20.millis).flatMap(_ => loop(remainingMs - 20))
    }
    loop(timeout.toMillis)
  }

  "ViewerStatePayload polymorphic registration (regression for bug #36)" should {
    "preserve case-class field schema in the polymorphic Definition" in Task {
      // The bug: registering via `RW.static(instance)` produced a
      // singleton-shaped RW whose `.definition` had no fields. The
      // fix: register `summon[RW[TestViewerState]]` directly so the
      // macro-derived field schema lands in the poly registry.
      // Spice's Dart codegen walks this exact path; if it sees
      // empty fields here the generated Dart class has empty fields.
      val polyDef = summon[RW[ViewerStatePayload]].definition
      val poly = polyDef.defType match {
        case p: DefType.Poly => p
        case other => fail(s"Expected Poly; saw $other")
      }
      val testEntry = poly.values.getOrElse(
        "TestViewerState",
        fail(
          s"Expected `TestViewerState` registered in the polymorphic Definition; saw ${poly.values.keySet}"
        ))
      val obj = testEntry.defType match {
        case o: DefType.Obj => o
        case other => fail(s"Expected Obj; saw $other")
      }
      obj.map.keySet should contain allOf ("activeTab", "panelOpen")
    }
  }

  "RequestViewerState" should {
    "reply with payload=None when no record exists for the (viewer, scope) pair" in {
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- TestSigil.handleNotice(RequestViewerState("ui-fresh"), TestUser)
        replies <- awaitSnapshots(recorded, "ui-fresh", 1)
      } yield {
        stop()
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
        _ <- awaitSnapshots(recorded, "ui-update", 1)
        // Now ask for it back — should round-trip the typed instance.
        _ <- TestSigil.handleNotice(RequestViewerState("ui-update"), TestUser)
        replies <- awaitSnapshots(recorded, "ui-update", 2)
      } yield {
        stop()
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
        a <- awaitSnapshots(qA, "ui-broadcast", 1)
        b <- awaitSnapshots(qB, "ui-broadcast", 1)
      } yield {
        stopA(); stopB()
        a.map(_.payload) shouldBe List(Some(payload))
        b.map(_.payload) shouldBe List(Some(payload))
      }
    }
  }

  "DeleteViewerState" should {
    "drop the row and broadcast a None snapshot" in {
      val (recorded, stop) = subscribe(TestUser)
      val payload = TestViewerState(activeTab = "tools", panelOpen = true)
      for {
        _ <- TestSigil.handleNotice(UpdateViewerState("ui-delete", payload), TestUser)
        _ <- awaitSnapshots(recorded, "ui-delete", 1)
        _ <- TestSigil.handleNotice(DeleteViewerState("ui-delete"), TestUser)
        _ <- awaitSnapshots(recorded, "ui-delete", 2)
        // Confirm the row is actually gone — request returns None.
        _ <- TestSigil.handleNotice(RequestViewerState("ui-delete"), TestUser)
        replies <- awaitSnapshots(recorded, "ui-delete", 3)
      } yield {
        stop()
        // Sequence: update→Some(payload), delete→None, request→None.
        replies.map(_.payload) shouldBe List(Some(payload), None, None)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

/**
 * Test-only payload — registered with the poly RW at spec init so
 * fabric can round-trip it through the wire shape.
 */
case class TestViewerState(activeTab: String, panelOpen: Boolean) extends ViewerStatePayload derives RW
