package spec

import java.util.concurrent.atomic.AtomicInteger

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.provider.{
  Complexity, ConversationWork, ModelCandidate, ProviderStrategy
}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * Regression for sigil bug #167 — when `pin_complexity` fires
 * mid-turn (or the user otherwise mutates conversation routing
 * state), subsequent iterations of the SAME user turn must see the
 * change. The pre-fix `routingCache` returned the cached
 * `(workType, complexity)` early on `userMessageId` match without
 * re-reading `conversation.pinnedComplexity`, so the very respond
 * iteration that confirmed the pin still routed off the OLD model.
 *
 * Fix: cache only the classifier output (immutable per-message);
 * derive effective routing fresh on every call from the conversation's
 * current pin + per-turn escalation counter.
 */
class MidTurnPinShadowingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelLow:    Id[Model] = Model.id("test", "low-only")
  private val modelMedium: Id[Model] = Model.id("test", "medium-only")
  private val modelHigh:   Id[Model] = Model.id("test", "high-only")

  private def routedStrategy(classifierCalls: AtomicInteger,
                             classifyAs: Complexity = Complexity.Low): ProviderStrategy =
    ProviderStrategy.routed(
      default = List(
        ModelCandidate(modelMedium, supportedComplexity = Set(Complexity.Medium))
      ),
      routes = Map(
        ConversationWork -> List(
          ModelCandidate(modelLow,    supportedComplexity = Set(Complexity.Low)),
          ModelCandidate(modelMedium, supportedComplexity = Set(Complexity.Medium)),
          ModelCandidate(modelHigh,   supportedComplexity = Set(Complexity.High))
        )
      ),
      inferWorkType   = Some((_, _) => Task.pure(ConversationWork)),
      inferComplexity = Some((_, _) => Task {
        classifierCalls.incrementAndGet()
        classifyAs
      })
    )

  private def freshConv(label: String, pinned: Option[Complexity] = None): Task[Conversation] = {
    val conv = Conversation(
      topics = TestTopicStack,
      _id = Conversation.id(s"$label-${rapid.Unique()}"),
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

  private def userMsgFor(conv: Conversation, text: String): Message = Message(
    participantId  = TestUser,
    conversationId = conv._id,
    topicId        = TestTopicEntry.id,
    content        = Vector(ResponseContent.Text(text)),
    state          = EventState.Complete
  )

  "Mid-turn pinnedComplexity changes" should {

    "surface on the next classifyForRoute call after a pin is set" in {
      val calls = new AtomicInteger(0)
      val strategy = routedStrategy(calls, classifyAs = Complexity.Low)
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv1   <- freshConv("pin-after")
        msg     = userMsgFor(conv1, "do a thing")
        // Iteration 1 (no pin): classifier runs → Low.
        iter1   <- TestSigil.classifyForRoute(strategy, ConversationWork, conv1, Some(msg), buildCtx(conv1))
        // Persist a pin mid-turn (what pin_complexity does).
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv1.copy(pinnedComplexity = Some(Complexity.Medium)))))
        conv2   <- TestSigil.withDB(_.conversations.transaction(_.get(conv1._id))).map(_.get)
        // Iteration 2 (pinned to Medium): must reflect the pin, NOT the cached Low.
        iter2   <- TestSigil.classifyForRoute(strategy, ConversationWork, conv2, Some(msg), buildCtx(conv2))
      } yield {
        iter1._2 shouldBe Complexity.Low
        iter2._2 shouldBe Complexity.Medium
        // Classifier should have run exactly once (memo serves iter2).
        calls.get() shouldBe 1
      }
    }

    "surface on the next classifyForRoute call after a pin is cleared" in {
      val calls = new AtomicInteger(0)
      val strategy = routedStrategy(calls, classifyAs = Complexity.Low)
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv1   <- freshConv("unpin-mid", pinned = Some(Complexity.High))
        msg     = userMsgFor(conv1, "do a thing")
        // Iteration 1 (pinned High): pin wins, classifier may not have run yet.
        iter1   <- TestSigil.classifyForRoute(strategy, ConversationWork, conv1, Some(msg), buildCtx(conv1))
        // Clear the pin mid-turn (what unpin_complexity does).
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv1.copy(pinnedComplexity = None))))
        conv2   <- TestSigil.withDB(_.conversations.transaction(_.get(conv1._id))).map(_.get)
        // Iteration 2 (no pin): must fall back to classifier output Low,
        // not stay stuck on the previously-pinned High.
        iter2   <- TestSigil.classifyForRoute(strategy, ConversationWork, conv2, Some(msg), buildCtx(conv2))
      } yield {
        iter1._2 shouldBe Complexity.High
        iter2._2 shouldBe Complexity.Low
      }
    }

    "surface on the next classifyForRoute call when the pin changes tier" in {
      val calls = new AtomicInteger(0)
      val strategy = routedStrategy(calls, classifyAs = Complexity.Low)
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv1 <- freshConv("repin", pinned = Some(Complexity.Medium))
        msg   = userMsgFor(conv1, "do a thing")
        iter1 <- TestSigil.classifyForRoute(strategy, ConversationWork, conv1, Some(msg), buildCtx(conv1))
        // Re-pin to High mid-turn.
        _     <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv1.copy(pinnedComplexity = Some(Complexity.High)))))
        conv2 <- TestSigil.withDB(_.conversations.transaction(_.get(conv1._id))).map(_.get)
        iter2 <- TestSigil.classifyForRoute(strategy, ConversationWork, conv2, Some(msg), buildCtx(conv2))
      } yield {
        iter1._2 shouldBe Complexity.Medium
        iter2._2 shouldBe Complexity.High
      }
    }
  }

  "Classifier memo" should {

    "run the classifier exactly once for a given userMessageId across iterations" in {
      val calls = new AtomicInteger(0)
      val strategy = routedStrategy(calls, classifyAs = Complexity.Low)
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv <- freshConv("memo")
        msg  = userMsgFor(conv, "same message")
        _    <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msg), buildCtx(conv))
        _    <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msg), buildCtx(conv))
        _    <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msg), buildCtx(conv))
      } yield calls.get() shouldBe 1
    }

    "re-classify when a new user message arrives" in {
      val calls = new AtomicInteger(0)
      val strategy = routedStrategy(calls, classifyAs = Complexity.Low)
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv <- freshConv("memo-new-msg")
        msgA = userMsgFor(conv, "first message")
        msgB = userMsgFor(conv, "second message")
        _    <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msgA), buildCtx(conv))
        _    <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msgB), buildCtx(conv))
      } yield calls.get() shouldBe 2
    }
  }

  "Per-turn escalations" should {

    "apply on top of the classifier complexity within one turn" in {
      val calls = new AtomicInteger(0)
      val strategy = routedStrategy(calls, classifyAs = Complexity.Low)
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv  <- freshConv("escalate")
        msg   = userMsgFor(conv, "harder than it looks")
        // Prime the classifier + escalation counter.
        iter1 <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msg), buildCtx(conv))
        bump1 <- TestSigil.requestEscalation(conv._id, reason = "test")
        iter2 <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msg), buildCtx(conv))
      } yield {
        iter1._2 shouldBe Complexity.Low
        bump1._1 shouldBe Complexity.Medium
        bump1._2 shouldBe true
        iter2._2 shouldBe Complexity.Medium
      }
    }

    "reset when a new user message arrives" in {
      val calls = new AtomicInteger(0)
      val strategy = routedStrategy(calls, classifyAs = Complexity.Low)
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv  <- freshConv("escalate-reset")
        msgA  = userMsgFor(conv, "first message")
        msgB  = userMsgFor(conv, "second message")
        _     <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msgA), buildCtx(conv))
        _     <- TestSigil.requestEscalation(conv._id, reason = "test")
        // New user message → escalation counter resets back to 0.
        iterB <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msgB), buildCtx(conv))
      } yield iterB._2 shouldBe Complexity.Low
    }

    "be ignored when a pin is active (pin binds regardless of escalation count)" in {
      val calls = new AtomicInteger(0)
      val strategy = routedStrategy(calls, classifyAs = Complexity.Low)
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      for {
        conv  <- freshConv("pin-vs-escalate", pinned = Some(Complexity.Medium))
        msg   = userMsgFor(conv, "anything")
        _     <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msg), buildCtx(conv))
        _     <- TestSigil.requestEscalation(conv._id, reason = "test")
        iter2 <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(msg), buildCtx(conv))
      } yield iter2._2 shouldBe Complexity.Medium
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
