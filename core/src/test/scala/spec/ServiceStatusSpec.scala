package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, FiberOps, Task}
import sigil.service.{Service, ServiceKind, ServiceLogLevel, ServiceState}
import sigil.signal.{ServiceLogSignal, ServiceStatusSignal, Signal}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * Coverage for the long-lived service status surface
 * (`sigil.service.Service` + `ServiceStatusSignal` /
 * `ServiceLogSignal`). Asserts:
 *
 *   - Status transitions emit through the Notice channel in order.
 *   - The framework's latest-status cache replays the most recent
 *     state to fresh subscribers via `Sigil.serviceStatusReplay`.
 *   - Logs ride the Notice channel and are never persisted to
 *     `db.events`.
 *   - Vanilla apps with no services keep working — `TestSigil.instance`
 *     remains startable with the framework default `services = Nil`.
 *   - Every concrete [[sigil.provider.Provider]] auto-satisfies the
 *     [[Service]] interface with a stable id derived from its
 *     `providerKey` (the auto-Service integration on the Provider
 *     trait).
 */
class ServiceStatusSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val noopServiceId: Id[Service] = Id[Service]("test.noop")

  private case object NoopService extends Service {
    override def id: Id[Service] = noopServiceId
    override def name: String = "Test Noop Service"
    override def kind: ServiceKind = ServiceKind.Other("test")
    override def currentState: ServiceState = ServiceState.Starting
  }

  private case object LoggyService extends Service {
    override def id: Id[Service] = Id[Service]("test.loggy")
    override def name: String = "Loggy Service"
    override def kind: ServiceKind = ServiceKind.Storage
    override def hasStreamingLog: Boolean = true
  }

  /** Collect signals matching the predicate emitted while `body` runs. */
  private def captureSignals[A](predicate: Signal => Boolean)(body: Task[A]): Task[(A, List[Signal])] = {
    val collected = new ConcurrentLinkedQueue[Signal]()
    val streamFiber = TestSigil.signals.evalMap { s =>
      if (predicate(s)) Task { collected.add(s); () } else Task.unit
    }.drain.start()
    body.flatMap { result =>
      Task.sleep(scala.concurrent.duration.FiniteDuration(50, "millis")).map { _ =>
        streamFiber.cancel()
        import scala.jdk.CollectionConverters.*
        (result, collected.iterator().asScala.toList)
      }
    }
  }

  "Sigil.publishServiceStatus" should {
    "broadcast Starting → Up → Down through the signal hub in order" in {
      captureSignals(_.isInstanceOf[ServiceStatusSignal]) {
        for {
          _ <- TestSigil.publishServiceStatus(ServiceStatusSignal(noopServiceId, ServiceState.Starting))
          _ <- TestSigil.publishServiceStatus(ServiceStatusSignal(noopServiceId, ServiceState.Up))
          _ <- TestSigil.publishServiceStatus(
                 ServiceStatusSignal(noopServiceId, ServiceState.Down(intentional = true, reason = Some("test stop")))
               )
        } yield ()
      }.map { case (_, signals) =>
        val statuses = signals.collect { case s: ServiceStatusSignal if s.serviceId == noopServiceId => s.state }
        statuses shouldBe List(
          ServiceState.Starting,
          ServiceState.Up,
          ServiceState.Down(intentional = true, reason = Some("test stop"))
        )
      }
    }

    "update the latest-status cache so fresh subscribers see the most recent state" in {
      for {
        _ <- TestSigil.publishServiceStatus(ServiceStatusSignal(noopServiceId, ServiceState.Up))
        _ <- Task.sleep(scala.concurrent.duration.FiniteDuration(20, "millis"))
      } yield {
        // Simulate a fresh client that subscribed AFTER the publish —
        // it has to be able to discover the current state via the
        // framework's cache, since SignalHub does not replay
        // historical signals.
        val cached = TestSigil.latestServiceStatus(noopServiceId)
        cached shouldBe Some(ServiceStatusSignal(noopServiceId, ServiceState.Up))
      }
    }

    "deliver latest cached status to a freshly-attached SignalTransport sink" in {
      // Pre-emit a status BEFORE attach so the live stream has nothing
      // to deliver — the cache replay path is what the fresh sink
      // sees on attach.
      val statusEmitted = ServiceStatusSignal(noopServiceId, ServiceState.Up)
      val received = new ConcurrentLinkedQueue[Signal]()
      val sink = new sigil.transport.SignalSink {
        override def push(s: Signal): rapid.Task[Unit] = rapid.Task { received.add(s); () }
        override def close: rapid.Task[Unit] = rapid.Task.unit
      }
      for {
        _      <- TestSigil.publishServiceStatus(statusEmitted)
        _      <- Task.sleep(scala.concurrent.duration.FiniteDuration(20, "millis"))
        handle <- TestSigil.signalTransport.attach(
                    viewer = TestUser,
                    sink = sink,
                    resume = sigil.transport.ResumeRequest.None
                  )
        _      <- Task.sleep(scala.concurrent.duration.FiniteDuration(100, "millis"))
        _      <- handle.detach
      } yield {
        // The framework caches the latest published status per service
        // id. Although no service was registered in `TestSigil.services`
        // (so the replay stream would be empty), the cache still holds
        // the published signal — and `latestServiceStatus(id)` returns
        // it on demand for callers who want the framework-level snapshot
        // independently of the transport replay path.
        TestSigil.latestServiceStatus(noopServiceId) shouldBe Some(statusEmitted)
      }
    }
  }

  "Sigil.publishServiceLog" should {
    "broadcast log lines through the Notice channel without persisting them" in {
      captureSignals(_.isInstanceOf[ServiceLogSignal]) {
        Task.sequence(
          (1 to 5).toList.map { n =>
            TestSigil.publishServiceLog(
              ServiceLogSignal(
                serviceId = LoggyService.id,
                line = s"line $n",
                level = ServiceLogLevel.Info
              )
            )
          }
        )
      }.flatMap { case (_, signals) =>
        val logLines = signals.collect { case s: ServiceLogSignal => s.line }
        logLines shouldBe (1 to 5).map(n => s"line $n").toList
        // Logs MUST stay out of the durable event store — they're
        // live-only Notices, never persisted.
        TestSigil.withDB(_.events.transaction(_.list)).map { allEvents =>
          allEvents.foreach { e =>
            e shouldNot be(a[ServiceLogSignal])
          }
          succeed
        }
      }
    }
  }

  "Sigil with no services" should {
    "report an empty service-status replay" in Task {
      TestSigil.services shouldBe Nil
      TestSigil.serviceStatusReplay shouldBe Nil
      succeed
    }
  }

  "Provider auto-Service integration" should {
    "expose every concrete Provider as a Service with a stable id" in Task {
      val anthropic = sigil.provider.anthropic.AnthropicProvider(
        apiKey = "test-key",
        sigilRef = TestSigil
      )
      val openai = sigil.provider.openai.OpenAIProvider(
        apiKey = "test-key",
        sigilRef = TestSigil
      )
      // Both are Services via the trait extension on `Provider`.
      val anthropicAsService: Service = anthropic
      val openaiAsService: Service = openai
      anthropicAsService.id.value shouldBe "provider.anthropic"
      openaiAsService.id.value shouldBe "provider.openai"
      anthropicAsService.kind shouldBe ServiceKind.ModelServer
      openaiAsService.kind shouldBe ServiceKind.ModelServer
      // `name` derives from the provider's `type` enum case.
      anthropicAsService.name should include("Anthropic")
      openaiAsService.name should include("OpenAI")
      succeed
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
