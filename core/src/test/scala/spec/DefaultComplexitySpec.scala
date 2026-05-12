package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.provider.{Complexity, ConversationWork, ModelCandidate, ProviderStrategy}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.TurnContext

/**
 * Regression for sigil bug #154 — the framework hard-coded
 * `Complexity.Medium` for empty-user-text turns + classifier
 * failures. Cost-first apps biased toward Low (every greet
 * should route to a local model) were silently overridden:
 * Sage's `greetsOnJoin = true` first turn picked a Medium-tier
 * paid hosted model regardless of how its candidate chain was
 * arranged.
 *
 * Fix: `ProviderStrategy.routed(..., defaultComplexity = …)`
 * knob threads into both fallback sites in
 * `Sigil.classifyForRoute`. Default `Complexity.Medium`
 * preserves existing behaviour.
 */
class DefaultComplexitySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "default-complexity-model")

  private def freshConv(label: String): Task[Conversation] = {
    val conv = Conversation(topics = TestTopicStack, _id = Conversation.id(s"$label-${rapid.Unique()}"))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_ => conv)
  }

  private def buildCtx(conv: Conversation): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      turnInput = sigil.conversation.TurnInput(conversationId = conv._id, frames = Vector.empty)
    )

  private def userMessage(conv: Conversation, content: String = ""): Option[Message] =
    if (content.isEmpty) None
    else Some(Message(
      participantId  = TestUser,
      conversationId = conv._id,
      topicId        = TestTopicEntry.id,
      content        = Vector(ResponseContent.Text(content)),
      state          = EventState.Complete
    ))

  "ProviderStrategy.routed.defaultComplexity" should {

    "default to Medium when not set (preserves existing behaviour)" in {
      // No classifier, no pin, no user text — the empty-text
      // fallback fires. Without an explicit defaultComplexity
      // override, we expect Medium.
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(modelId,                   supportedComplexity = Set(Complexity.Low)),
          ModelCandidate(Model.id("test", "med"),   supportedComplexity = Set(Complexity.Medium)),
          ModelCandidate(Model.id("test", "hi"),    supportedComplexity = Set(Complexity.High))
        )
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv   <- freshConv("default-medium")
        result <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, None, buildCtx(conv))
      } yield {
        result._2 shouldBe Complexity.Medium
      }
    }

    "honour an explicit Low override on empty-text turns (greet path)" in {
      // The bug's reference case: app explicitly biases to Low
      // for cost-first defaults. Empty-text greets must route
      // there instead of Medium.
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(modelId,                   supportedComplexity = Set(Complexity.Low)),
          ModelCandidate(Model.id("test", "med"),   supportedComplexity = Set(Complexity.Medium))
        ),
        defaultComplexity = Complexity.Low
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv   <- freshConv("default-low")
        result <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, None, buildCtx(conv))
      } yield {
        result._2 shouldBe Complexity.Low
      }
    }

    "honour the override when the classifier fails" in {
      // Classifier raises — empty-text gate doesn't fire (user
      // text non-empty), inferComplexity runs, throws, falls
      // back to defaultComplexity.
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(modelId,                 supportedComplexity = Set(Complexity.Low)),
          ModelCandidate(Model.id("test", "hi"),  supportedComplexity = Set(Complexity.High))
        ),
        routes = Map(
          ConversationWork -> List(
            ModelCandidate(modelId,                supportedComplexity = Set(Complexity.Low)),
            ModelCandidate(Model.id("test", "hi"), supportedComplexity = Set(Complexity.High))
          )
        ),
        inferComplexity   = Some((_, _) => Task.error(new RuntimeException("classifier offline"))),
        inferWorkType     = Some((_, _) => Task.pure(ConversationWork)),
        defaultComplexity = Complexity.Low
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv   <- freshConv("classifier-fail")
        result <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, userMessage(conv, "anything"), buildCtx(conv))
      } yield {
        result._2 shouldBe Complexity.Low
      }
    }

    "skip the override when a pin is set (pin wins over both classifier + default)" in {
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(modelId,                supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
        ),
        defaultComplexity = Complexity.Low
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      val convId = Conversation.id(s"pin-wins-${rapid.Unique()}")
      val pinned = Conversation(
        topics = TestTopicStack,
        _id = convId,
        pinnedComplexity = Some(Complexity.High)
      )
      for {
        _      <- TestSigil.withDB(_.conversations.transaction(_.upsert(pinned)))
        result <- TestSigil.classifyForRoute(strategy, ConversationWork, pinned, None, buildCtx(pinned))
      } yield {
        result._2 shouldBe Complexity.High
      }
    }

    "skip the override when the classifier returns a real value" in {
      // Classifier runs successfully — override doesn't apply.
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(modelId,                 supportedComplexity = Set(Complexity.Low)),
          ModelCandidate(Model.id("test", "hi"),  supportedComplexity = Set(Complexity.High))
        ),
        routes = Map(
          ConversationWork -> List(
            ModelCandidate(modelId,                supportedComplexity = Set(Complexity.Low)),
            ModelCandidate(Model.id("test", "hi"), supportedComplexity = Set(Complexity.High))
          )
        ),
        inferComplexity   = Some((_, _) => Task.pure(Complexity.High)),
        inferWorkType     = Some((_, _) => Task.pure(ConversationWork)),
        defaultComplexity = Complexity.Low
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv   <- freshConv("classifier-ok")
        result <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, userMessage(conv, "any text"), buildCtx(conv))
      } yield {
        result._2 shouldBe Complexity.High
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
