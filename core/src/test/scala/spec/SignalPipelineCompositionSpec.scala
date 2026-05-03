package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.{Message, MessageRole}
import sigil.pipeline.{InboundTransform, SettledEffect}
import sigil.signal.{EventState, Signal}
import sigil.tool.model.ResponseContent

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.collection.mutable

/**
 * Verifies the contract of the signal pipeline's two extension
 * points — [[InboundTransform]] and [[SettledEffect]] — under
 * composition with multiple instances and with error conditions.
 *
 * The framework's documented invariants this spec locks in:
 *
 *   1. Inbound transforms run in declaration order; each sees the
 *      output of the previous.
 *   2. Settled effects run in declaration order after the signal has
 *      been persisted + projected.
 *   3. A transform that throws fails the publish — caller observes
 *      the exception (signal is NOT persisted).
 *   4. An effect that throws WITHOUT handling its own error propagates
 *      the failure through `publish` and stops subsequent effects from
 *      running. The framework's contract on [[SettledEffect]] is that
 *      "effect failures should be handled by the effect itself" — apps
 *      that want resilience wrap their effect's body in `handleError`.
 *
 * Drives composition via a one-off Sigil that overrides
 * `inboundTransforms` and `settledEffects` so each test is hermetic.
 */
class SignalPipelineCompositionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /** Build a fresh Sigil for the test, with the supplied transforms +
    * effects wired. We don't share TestSigil because the override
    * lists would leak across specs. */
  private def buildSigil(transformsList: List[InboundTransform],
                          effectsList: List[SettledEffect],
                          dbName: String): Sigil = {
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(s"db/test/SignalPipelineCompositionSpec-$dbName"))))
    new Sigil {
      override type DB = sigil.db.DefaultSigilDB
      override protected def buildDB(directory: Option[java.nio.file.Path],
                                     storeManager: lightdb.store.CollectionManager,
                                     appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
        new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)
      override def providerFor(modelId: lightdb.id.Id[sigil.db.Model],
                                chain: List[sigil.participant.ParticipantId]): rapid.Task[sigil.provider.Provider] =
        rapid.Task.error(new RuntimeException("provider unused"))
      override def inboundTransforms: List[InboundTransform] = transformsList
      override def settledEffects: List[SettledEffect] = effectsList
      override protected def participantIds: List[fabric.rw.RW[? <: sigil.participant.ParticipantId]] =
        List(fabric.rw.RW.static(TestUser), fabric.rw.RW.static(TestAgent))
    }
  }

  private def deleteDb(name: String): Unit = {
    val p = java.nio.file.Path.of("db", "test", s"SignalPipelineCompositionSpec-$name")
    if (java.nio.file.Files.exists(p)) {
      val stream = java.nio.file.Files.walk(p)
      try {
        import scala.jdk.CollectionConverters.*
        stream.iterator().asScala.toList.reverse.foreach(java.nio.file.Files.deleteIfExists(_))
      } finally stream.close()
    }
  }

  private def syntheticMessage(text: String): Message = Message(
    participantId = TestUser,
    conversationId = Conversation.id(s"sig-pipe-${rapid.Unique()}"),
    topicId = TestTopicId,
    content = Vector(ResponseContent.Text(text)),
    state = EventState.Complete,
    role = MessageRole.Standard
  )

  /** Inbound transform that prepends a tag to Message text and records
    * it ran. Composes with sibling transforms by passing the rewritten
    * signal through. */
  private class TaggingTransform(tag: String, log: mutable.ListBuffer[String]) extends InboundTransform {
    override def apply(signal: Signal, self: Sigil): Task[Signal] = signal match {
      case m: Message =>
        log.synchronized { log += tag }
        val rewritten = m.copy(content = m.content.map {
          case ResponseContent.Text(t) => ResponseContent.Text(s"[$tag] $t")
          case other => other
        })
        Task.pure(rewritten)
      case other => Task.pure(other)
    }
  }

  /** Inbound transform that throws on every Message — used to verify
    * that a transform-level exception fails the publish. */
  private object ExplodingTransform extends InboundTransform {
    override def apply(signal: Signal, self: Sigil): Task[Signal] =
      Task.error(new RuntimeException("synthetic transform failure"))
  }

  /** Settled effect that records its invocation. */
  private class RecordingEffect(name: String, log: mutable.ListBuffer[String]) extends SettledEffect {
    override def apply(signal: Signal, self: Sigil): Task[Unit] = Task {
      log.synchronized { log += name }
      ()
    }
  }

  /** Settled effect that throws — used to verify subsequent effects
    * still run despite this one's failure. */
  private object FailingEffect extends SettledEffect {
    val invoked: AtomicInteger = new AtomicInteger(0)
    override def apply(signal: Signal, self: Sigil): Task[Unit] = {
      invoked.incrementAndGet()
      Task.error(new RuntimeException("synthetic effect failure"))
    }
  }

  "Sigil.publish — inbound transforms" should {

    "run multiple transforms in declaration order, each seeing the previous output" in {
      val log = mutable.ListBuffer.empty[String]
      val name = s"transforms-order-${rapid.Unique()}"
      deleteDb(name)
      val s = buildSigil(
        transformsList = List(new TaggingTransform("A", log), new TaggingTransform("B", log)),
        effectsList = Nil,
        dbName = name
      )
      val inbound = syntheticMessage("hello")
      for {
        _ <- s.instance
        _ <- s.publish(inbound)
        // Read the persisted message back to confirm both transforms applied.
        events <- s.withDB(_.events.transaction(_.list))
      } yield {
        log.toList shouldBe List("A", "B")  // declaration order
        // Both tags should be applied in order: B(A(input)) → "[B] [A] hello".
        val persisted = events.collectFirst { case m: Message if m._id == inbound._id => m }
        persisted should not be empty
        val text = persisted.get.content.collectFirst { case ResponseContent.Text(t) => t }
        text shouldBe Some("[B] [A] hello")
      }
    }

    "fail the publish when a transform throws — signal is NOT persisted" in {
      val name = s"transform-throws-${rapid.Unique()}"
      deleteDb(name)
      val s = buildSigil(
        transformsList = List(ExplodingTransform),
        effectsList = Nil,
        dbName = name
      )
      val inbound = syntheticMessage("never persisted")
      for {
        _ <- s.instance
        publishResult <- s.publish(inbound).attempt
        events <- s.withDB(_.events.transaction(_.list))
      } yield {
        publishResult.isFailure shouldBe true
        publishResult.failed.get.getMessage should include("synthetic transform failure")
        // The persistence path never ran — events store has no Message.
        events.collectFirst { case m: Message if m._id == inbound._id => m } shouldBe empty
      }
    }
  }

  "Sigil.publish — settled effects" should {

    "run multiple effects in declaration order" in {
      val log = mutable.ListBuffer.empty[String]
      val name = s"effects-order-${rapid.Unique()}"
      deleteDb(name)
      val s = buildSigil(
        transformsList = Nil,
        effectsList = List(new RecordingEffect("first", log), new RecordingEffect("second", log)),
        dbName = name
      )
      for {
        _ <- s.instance
        _ <- s.publish(syntheticMessage("ordered effects"))
      } yield {
        log.toList shouldBe List("first", "second")
      }
    }

    "propagate an unhandled effect failure through publish, halting subsequent effects" in {
      // The framework's contract: effects must self-handle errors.
      // Unhandled failures propagate. Apps that want resilience wrap
      // their effect's body in `Task.handleError`.
      val log = mutable.ListBuffer.empty[String]
      val name = s"effect-throws-${rapid.Unique()}"
      deleteDb(name)
      FailingEffect.invoked.set(0)
      val s = buildSigil(
        transformsList = Nil,
        effectsList = List(
          new RecordingEffect("before-fail", log),
          FailingEffect,
          new RecordingEffect("never-runs", log)
        ),
        dbName = name
      )
      for {
        _ <- s.instance
        publishResult <- s.publish(syntheticMessage("effect-error-propagates")).attempt
      } yield {
        publishResult.isFailure shouldBe true
        publishResult.failed.get.getMessage should include("synthetic effect failure")
        FailingEffect.invoked.get() shouldBe 1
        // before-fail ran; never-runs did NOT (the chain stopped).
        log.toList shouldBe List("before-fail")
      }
    }

    "isolate failures when the effect handles them itself (apps' resilience pattern)" in {
      // Documented pattern for resilient effects: wrap the effect's body
      // in handleError. This spec verifies the pattern works — the
      // failure is contained and subsequent effects run.
      val log = mutable.ListBuffer.empty[String]
      val name = s"effect-handles-${rapid.Unique()}"
      deleteDb(name)
      val resilientFailing = new SettledEffect {
        override def apply(signal: Signal, self: Sigil): Task[Unit] =
          Task.error(new RuntimeException("contained failure"))
            .handleError(_ => Task.unit)  // app-side resilience
      }
      val s = buildSigil(
        transformsList = Nil,
        effectsList = List(
          new RecordingEffect("first", log),
          resilientFailing,
          new RecordingEffect("third", log)
        ),
        dbName = name
      )
      for {
        _ <- s.instance
        _ <- s.publish(syntheticMessage("effect-error-contained"))
      } yield {
        log.toList shouldBe List("first", "third")
      }
    }
  }
}
