package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.tool.model.{
  RandomChoiceInput, RandomChoiceOutput,
  RandomDoubleInput, RandomDoubleOutput,
  RandomIntInput, RandomIntOutput,
  RandomUuidInput, RandomUuidOutput
}
import sigil.tool.random.{RandomChoiceTool, RandomDoubleTool, RandomIntTool, RandomUuidTool}

class RandomToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("random-tool-spec-conv")
  private val ctx: TurnContext = TurnContext(
    sigil = TestSigil,
    chain = List(TestUser),
    conversation = Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      _id = convId
    ),
    turnInput = TurnInput(ConversationView(conversationId = convId))
  )

  "RandomIntTool" should {
    "return values within `[min, max]` inclusive" in {
      val task = rapid.Task.sequence(
        (1 to 200).toList.map(_ => RandomIntTool.invoke(RandomIntInput(min = 1, max = 6), ctx))
      )
      task.map { results =>
        all(results.map(_.value)) should (be >= 1L and be <= 6L)
        all(results.map(_.min)) shouldBe 1L
        all(results.map(_.max)) shouldBe 6L
      }
    }

    "produce identical values for the same seed" in {
      for {
        a <- RandomIntTool.invoke(RandomIntInput(min = 0, max = 1_000_000, seed = Some(42L)), ctx)
        b <- RandomIntTool.invoke(RandomIntInput(min = 0, max = 1_000_000, seed = Some(42L)), ctx)
      } yield {
        a.value shouldBe b.value
        a.seed shouldBe Some(42L)
      }
    }

    "produce different values for distinct seeds (overwhelmingly likely)" in {
      for {
        a <- RandomIntTool.invoke(RandomIntInput(min = 0, max = Int.MaxValue.toLong, seed = Some(1L)), ctx)
        b <- RandomIntTool.invoke(RandomIntInput(min = 0, max = Int.MaxValue.toLong, seed = Some(2L)), ctx)
      } yield a.value should not be b.value
    }

    "respect a degenerate min == max range" in
      RandomIntTool.invoke(RandomIntInput(min = 7, max = 7), ctx).map { out =>
        out.value shouldBe 7L
      }

    "fail when min > max" in {
      val attempt = RandomIntTool.invoke(RandomIntInput(min = 10, max = 5), ctx).attempt
      attempt.map(_.isFailure shouldBe true)
    }
  }

  "RandomDoubleTool" should {
    "return values within `[min, max)`" in {
      val task = rapid.Task.sequence(
        (1 to 200).toList.map(_ => RandomDoubleTool.invoke(RandomDoubleInput(min = -1.0, max = 1.0), ctx))
      )
      task.map { results =>
        all(results.map(_.value)) should (be >= -1.0 and be < 1.0)
      }
    }

    "be reproducible under a fixed seed" in {
      for {
        a <- RandomDoubleTool.invoke(RandomDoubleInput(seed = Some(13L)), ctx)
        b <- RandomDoubleTool.invoke(RandomDoubleInput(seed = Some(13L)), ctx)
      } yield a.value shouldBe b.value
    }
  }

  "RandomChoiceTool" should {
    "pick an element from `items` and report its index" in {
      val items = List("alpha", "beta", "gamma", "delta")
      val task = rapid.Task.sequence(
        (1 to 100).toList.map(_ => RandomChoiceTool.invoke(RandomChoiceInput(items = items), ctx))
      )
      task.map { results =>
        all(results.map(_.itemCount)) shouldBe items.size
        all(results.map(_.index)) should (be >= 0 and be < items.size)
        all(results.map(r => r.chosen == items(r.index))) shouldBe true
      }
    }

    "be reproducible under a fixed seed" in {
      val items = List("a", "b", "c", "d", "e")
      for {
        a <- RandomChoiceTool.invoke(RandomChoiceInput(items = items, seed = Some(99L)), ctx)
        b <- RandomChoiceTool.invoke(RandomChoiceInput(items = items, seed = Some(99L)), ctx)
      } yield {
        a.chosen shouldBe b.chosen
        a.index shouldBe b.index
      }
    }

    "fail on an empty `items` list" in {
      val attempt = RandomChoiceTool.invoke(RandomChoiceInput(items = Nil), ctx).attempt
      attempt.map(_.isFailure shouldBe true)
    }
  }

  "RandomUuidTool" should {
    "produce well-formed v4 UUID strings" in {
      val pattern = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}".r
      RandomUuidTool.invoke(RandomUuidInput(), ctx).map { out =>
        pattern.matches(out.uuid) shouldBe true
      }
    }

    "produce distinct values across calls (overwhelmingly likely)" in {
      for {
        a <- RandomUuidTool.invoke(RandomUuidInput(), ctx)
        b <- RandomUuidTool.invoke(RandomUuidInput(), ctx)
      } yield a.uuid should not be b.uuid
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
