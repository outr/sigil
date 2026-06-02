package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.ToolOutcome
import sigil.provider.AnalysisWork
import sigil.role.Role
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.model.DelegateTaskInput
import sigil.tool.util.DelegateTaskTool
import sigil.event.Event

/**
 * Coverage for `delegate_task`'s input round-trip and its caller
 * precondition: because the tool makes the calling agent the worker's
 * supervisor (sigil #327), it must be invoked by an agent participant of
 * the conversation and returns a structured error otherwise. End-to-end
 * worker spawning (the two-agent sub-conversation) is covered by
 * [[DelegationBridgeSpec]].
 */
class DelegateTaskToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("delegate-task-spec")

  private def turnContext(): TurnContext = {
    val conv = Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      _id    = convId
    )
    TurnContext(
      sigil            = TestSigil,
      chain            = List(TestUser),
      conversation     = conv,
      turnInput        = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
  }

  private def sampleInput: DelegateTaskInput = DelegateTaskInput(
    role = "researcher",
    roleDescription = Some("Research and synthesize."),
    brief = "Find recent papers on RAG",
    modelId = Some("anthropic/claude-sonnet-4-6")
  )

  private def turnContextFor(cid: Id[Conversation]): TurnContext = {
    val conv = Conversation(topics = List(TopicEntry(TestTopicId, "test", "test")), _id = cid)
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser),
      conversation = conv,
      turnInput    = TurnInput(ConversationView(conversationId = cid)),
      model        = TestSigil.defaultTestModel
    )
  }

  /** Persist root (depth 0) → worker (depth 1) → sub-worker (depth 2). */
  private def persistChain(): Task[(Id[Conversation], Id[Conversation], Id[Conversation])] = {
    val topic = List(TopicEntry(TestTopicId, "t", "t"))
    val root  = Conversation.id(s"d348-root-${rapid.Unique()}")
    val wkr   = Conversation.id(s"d348-worker-${rapid.Unique()}")
    val sub   = Conversation.id(s"d348-sub-${rapid.Unique()}")
    val up = (c: Conversation) => TestSigil.withDB(_.conversations.transaction(_.upsert(c)))
    for {
      _ <- up(Conversation(topics = topic, _id = root))
      _ <- up(Conversation(topics = topic, parentConversationId = Some(root), _id = wkr))
      _ <- up(Conversation(topics = topic, parentConversationId = Some(wkr), _id = sub))
    } yield (root, wkr, sub)
  }

  private def failureText(signals: List[Signal]): String =
    signals.collectFirst {
      case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) =>
        d.summary.getOrElse(d.outcome.collect { case ToolOutcome.Failure(r, _) => r }.getOrElse(""))
    }.getOrElse("")

  "DelegateTaskInput" should {
    "round-trip through fabric RW" in {
      import fabric.rw.*
      val rw = summon[RW[DelegateTaskInput]]
      rw.write(rw.read(sampleInput)) shouldBe sampleInput
      rapid.Task.pure(succeed)
    }
  }

  "DelegateTaskTool" should {
    "refuse when the caller is not an agent participant of the conversation" in {
      // delegate_task makes the caller the worker's supervisor, so it
      // must be an agent participant of the conversation. The turn here
      // is anchored as `TestUser` in a conversation with no agent
      // participants, so the tool refuses with a structured error.
      // (Registered model id so the modelId precondition passes first.)
      val input = sampleInput.copy(modelId = Some(TestSigil.defaultTestModel._id.value))
      DelegateTaskTool.execute(input, turnContext(), Event.id()).toList.map { signals =>
        failureText(signals) should include("agent participant")
      }
    }

    // Sigil #348 — structural depth cap.
    "compute delegation depth by walking parentConversationId" in {
      persistChain().flatMap { case (root, worker, sub) =>
        for {
          d0 <- TestSigil.delegationDepth(root)
          d1 <- TestSigil.delegationDepth(worker)
          d2 <- TestSigil.delegationDepth(sub)
        } yield {
          d0 shouldBe 0
          d1 shouldBe 1
          d2 shouldBe 2
        }
      }
    }

    "refuse a delegation that would exceed maxDelegationDepth (worker re-delegation runaway guard)" in {
      // From the depth-2 sub-worker, spawning another worker would be
      // depth 3 > the default cap of 2 — refused before any spawn (and
      // before the supervisor-participant check), so no agent participant
      // is needed on the conversation.
      TestSigil.maxDelegationDepth shouldBe 2
      persistChain().flatMap { case (_, _, sub) =>
        val input = sampleInput.copy(modelId = Some(TestSigil.defaultTestModel._id.value))
        DelegateTaskTool.execute(input, turnContextFor(sub), Event.id()).toList.map { signals =>
          failureText(signals).toLowerCase should include("depth cap")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
