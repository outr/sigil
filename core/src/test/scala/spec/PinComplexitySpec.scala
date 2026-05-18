package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.provider.{
  AnalysisWork, Complexity, ConversationMode, ConversationRequest, ConversationWork,
  GenerationSettings, Instructions, ModelCandidate, ProviderStrategy, WorkType
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.ResponseContent
import sigil.tool.provider.{PinComplexityInput, PinComplexityTool, UnpinComplexityInput, UnpinComplexityTool}
import sigil.TurnContext

/**
 * Regression for sigil bug #152 — there's no way to pin a
 * conversation to a specific [[Complexity]] tier without naming
 * a specific model. `pin_model` exists but locks to ONE model;
 * `pin_complexity` should lock to a TIER and let the routing
 * chain pick whichever candidate handles it.
 *
 * Verifies:
 *   - `Conversation.pinnedComplexity` exists + persists
 *   - `classifyForRoute` honours the pin over the classifier
 *   - `PinComplexityTool` parses tier names + writes the field
 *   - `UnpinComplexityTool` clears it
 */
class PinComplexitySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "pin-complexity-model")

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

  "Conversation.pinnedComplexity" should {

    "default to None on fresh conversations" in
      freshConv("default").map { conv =>
        conv.pinnedComplexity shouldBe None
      }

    "persist and round-trip when set" in
      freshConv("persist", pinned = Some(Complexity.High)).flatMap { conv =>
        TestSigil.withDB(_.conversations.transaction(_.get(conv._id))).map { reloaded =>
          reloaded.flatMap(_.pinnedComplexity) shouldBe Some(Complexity.High)
        }
      }
  }

  "classifyForRoute" should {

    "honour pinnedComplexity over the strategy's inferComplexity" in {
      // Strategy says inferComplexity → Low, but conv pins High.
      // Expected: classifier returns High (pin wins).
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(modelId, supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
        ),
        routes = Map(
          ConversationWork -> List(
            ModelCandidate(modelId, supportedComplexity = Set(Complexity.Low)),
            ModelCandidate(Model.id("test", "hi"), supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
          )
        ),
        inferWorkType = Some((_, _) => Task.pure(ConversationWork)),
        inferComplexity = Some((_, _) => Task.pure(Complexity.Low))
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv <- freshConv("classify-pin", pinned = Some(Complexity.High))
        userMsg = Message(
          participantId = TestUser,
          conversationId = conv._id,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("trivial")),
          state = EventState.Complete
        )
        result <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg), buildCtx(conv))
      } yield result._2 shouldBe Complexity.High
    }

    "use the classifier when pinnedComplexity is None" in {
      val strategy = ProviderStrategy.routed(
        default = List(ModelCandidate(modelId, supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))),
        routes = Map(
          ConversationWork -> List(
            ModelCandidate(modelId, supportedComplexity = Set(Complexity.Low)),
            ModelCandidate(Model.id("test", "med"), supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
          )
        ),
        inferWorkType = Some((_, _) => Task.pure(ConversationWork)),
        inferComplexity = Some((_, _) => Task.pure(Complexity.Medium))
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv <- freshConv("classify-none")
        userMsg = Message(
          participantId = TestUser,
          conversationId = conv._id,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("some content")),
          state = EventState.Complete
        )
        result <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg), buildCtx(conv))
      } yield result._2 shouldBe Complexity.Medium
    }
  }

  "PinComplexityTool" should {

    "parse tier names and write pinnedComplexity" in {
      for {
        conv <- freshConv("pin-tool")
        ctx = buildCtx(conv)
        _ <- PinComplexityTool.execute(PinComplexityInput("high"), ctx).toList
        reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
      } yield reloaded.flatMap(_.pinnedComplexity) shouldBe Some(Complexity.High)
    }

    "accept multiple normalisations of the same tier" in {
      // "very-high", "veryhigh", "Very High" — all should map to VeryHigh.
      val normalisations = List("very-high", "very_high", "veryhigh", "VERY HIGH", "Very High")
      Task.sequence(normalisations.map { raw =>
        for {
          conv <- freshConv(s"normalise-${raw.replaceAll("\\W", "")}")
          ctx = buildCtx(conv)
          _ <- PinComplexityTool.execute(PinComplexityInput(raw), ctx).toList
          reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
        } yield reloaded.flatMap(_.pinnedComplexity)
      }).map { results =>
        results.foreach(_ shouldBe Some(Complexity.VeryHigh))
        succeed
      }
    }

    "reject unrecognised tier names without mutating state" in {
      for {
        conv <- freshConv("reject")
        ctx = buildCtx(conv)
        _ <- PinComplexityTool.execute(PinComplexityInput("ultra"), ctx).toList
        reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
      } yield reloaded.flatMap(_.pinnedComplexity) shouldBe None
    }
  }

  "UnpinComplexityTool" should {

    "clear pinnedComplexity when present" in {
      for {
        conv <- freshConv("unpin", pinned = Some(Complexity.High))
        ctx = buildCtx(conv)
        _ <- UnpinComplexityTool.execute(UnpinComplexityInput(), ctx).toList
        reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
      } yield reloaded.flatMap(_.pinnedComplexity) shouldBe None
    }

    "no-op when nothing pinned" in {
      for {
        conv <- freshConv("unpin-noop")
        ctx = buildCtx(conv)
        _ <- UnpinComplexityTool.execute(UnpinComplexityInput(), ctx).toList
        reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
      } yield reloaded.flatMap(_.pinnedComplexity) shouldBe None
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
