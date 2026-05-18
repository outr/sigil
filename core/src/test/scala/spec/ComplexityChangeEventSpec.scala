package spec

import lightdb.id.Id
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{ComplexityChange, Event, Message, MessageVisibility}
import sigil.provider.Complexity
import sigil.tool.provider.{PinComplexityInput, PinComplexityTool, UnpinComplexityInput, UnpinComplexityTool}
import sigil.TurnContext

/**
 * Regression for Sigil bug #177 — `ComplexityChange` event, symmetric
 * with `ModeChange`. UI consumers reduce off this event to maintain a
 * "current tier" indicator without polling `Conversation.pinnedComplexity`
 * or snooping on `RouteResolved` (which fires every turn).
 */
class ComplexityChangeEventSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "complexity-change-model")

  private def freshConv(label: String, pinned: Option[Complexity] = None): Task[Conversation] = {
    val convId = Conversation.id(s"$label-${rapid.Unique()}")
    val conv = Conversation(
      topics = TestTopicStack,
      _id = convId,
      pinnedComplexity = pinned
    )
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_ => conv)
  }

  private def buildCtx(conv: Conversation): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      turnInput = sigil.conversation.TurnInput(conversationId = conv._id, frames = Vector.empty)
    )

  "Bug #177 — PinComplexityTool emits ComplexityChange" should {

    "fire with Reason.Pinned + previousTier=None when no prior pin" in {
      for {
        conv <- freshConv("pin-first")
        events <- PinComplexityTool.execute(PinComplexityInput("medium"), buildCtx(conv)).toList
      } yield {
        val ccs = events.collect { case cc: ComplexityChange => cc }
        ccs should have size 1
        ccs.head.reason shouldBe ComplexityChange.Reason.Pinned
        ccs.head.previousTier shouldBe None
        ccs.head.newTier shouldBe Some(Complexity.Medium)
      }
    }

    "fire with Reason.Repinned + previousTier=Some(prior) when replacing a pin" in {
      for {
        conv <- freshConv("pin-repin", pinned = Some(Complexity.Medium))
        events <- PinComplexityTool.execute(PinComplexityInput("high"), buildCtx(conv)).toList
      } yield {
        val ccs = events.collect { case cc: ComplexityChange => cc }
        ccs should have size 1
        ccs.head.reason shouldBe ComplexityChange.Reason.Repinned
        ccs.head.previousTier shouldBe Some(Complexity.Medium)
        ccs.head.newTier shouldBe Some(Complexity.High)
      }
    }

    "emit ComplexityChange BEFORE the confirmation Message (reducers update first)" in {
      for {
        conv <- freshConv("pin-order")
        events <- PinComplexityTool.execute(PinComplexityInput("low"), buildCtx(conv)).toList
      } yield {
        val ccIdx = events.indexWhere(_.isInstanceOf[ComplexityChange])
        val msgIdx = events.indexWhere(_.isInstanceOf[Message])
        ccIdx should be >= 0
        msgIdx should be >= 0
        ccIdx should be < msgIdx
      }
    }

    "use visibility=All so UI consumers receive the event" in {
      for {
        conv <- freshConv("pin-vis")
        events <- PinComplexityTool.execute(PinComplexityInput("medium"), buildCtx(conv)).toList
      } yield {
        val cc = events.collectFirst { case cc: ComplexityChange => cc }.value
        cc.visibility shouldBe MessageVisibility.All
      }
    }

    "NOT fire when the tier string is unrecognised" in {
      for {
        conv <- freshConv("pin-unrecognised")
        events <- PinComplexityTool.execute(PinComplexityInput("ultra"), buildCtx(conv)).toList
      } yield events.collect { case cc: ComplexityChange => cc } shouldBe empty
    }
  }

  "Bug #177 — UnpinComplexityTool emits ComplexityChange" should {

    "fire with Reason.Unpinned + previousTier=Some(prior), newTier=None" in {
      for {
        conv <- freshConv("unpin-prior", pinned = Some(Complexity.High))
        events <- UnpinComplexityTool.execute(UnpinComplexityInput(), buildCtx(conv)).toList
      } yield {
        val ccs = events.collect { case cc: ComplexityChange => cc }
        ccs should have size 1
        ccs.head.reason shouldBe ComplexityChange.Reason.Unpinned
        ccs.head.previousTier shouldBe Some(Complexity.High)
        ccs.head.newTier shouldBe None
      }
    }

    "still emit when nothing was pinned (UI sees user intent)" in {
      for {
        conv <- freshConv("unpin-noop")
        events <- UnpinComplexityTool.execute(UnpinComplexityInput(), buildCtx(conv)).toList
      } yield {
        val ccs = events.collect { case cc: ComplexityChange => cc }
        ccs should have size 1
        ccs.head.previousTier shouldBe None
        ccs.head.newTier shouldBe None
      }
    }
  }

  "Bug #177 — projection arm" should {

    "project a published ComplexityChange onto Conversation.pinnedComplexity" in {
      for {
        conv <- freshConv("project")
        cc = ComplexityChange(
          participantId = TestUser,
          conversationId = conv._id,
          topicId = conv.currentTopicId,
          previousTier = None,
          newTier = Some(Complexity.VeryHigh),
          reason = ComplexityChange.Reason.Pinned
        )
        _ <- TestSigil.publish(cc)
        _ <- Task.sleep(scala.concurrent.duration.FiniteDuration(200, "ms"))
        reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
      } yield reloaded.flatMap(_.pinnedComplexity) shouldBe Some(Complexity.VeryHigh)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
