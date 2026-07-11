package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason, ToolPolicy
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent
import sigil.tool.util.{LookupTool, SleepTool}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicReference

/**
 * Sigil — `Sigil.currentParticipant` reconciles a persisted participant
 * against the app's current definition immediately before a turn. A
 * conversation's participants are a snapshot from creation, so an agent's
 * roster is otherwise frozen for the conversation's life — and a
 * discovery-suppressed roster (`ToolPolicy.ActiveOnly`, no `find_capability`)
 * has no way to reach a tool added after creation. This pins that the hook's
 * result drives the wire roster, while the persisted conversation is left
 * untouched (per-turn reconciliation, not a re-persist).
 */
class CurrentParticipantReconcileSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "reconcile")
  TestSigil.testModel(modelId)

  /**
   * Records the tool roster of the last call it received, then settles the
   * turn with a plain assistant message.
   */
  final private class RosterRecordingProvider(seen: AtomicReference[Vector[ToolName]]) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      seen.set(input.tools.map(_.name))
      val cid = CallId("reconcile-0")
      Stream.emits[ProviderEvent](List(
        ProviderEvent.ContentBlockDelta(cid, "ok"),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /**
   * A frozen agent whose roster is exactly `[lookup]` and whose policy
   * suppresses `find_capability` (ActiveOnly) — so nothing but the snapshot
   * roster could ever reach the wire.
   */
  private def agentWith(tools: List[ToolName]): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = tools,
      tools = ToolPolicy.ActiveOnly(tools),
      instructions = Instructions(),
      generationSettings = GenerationSettings())

  private def runTurnCapturingRoster(convId: Id[Conversation]): Task[Vector[ToolName]] = {
    val seen = new AtomicReference[Vector[ToolName]](Vector.empty)
    TestSigil.setProvider(Task.pure(new RosterRecordingProvider(seen)))
    val frozen = agentWith(List(LookupTool.name))
    val conv = Conversation(topics = TestTopicStack, participants = List(frozen), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("hello")),
        state = EventState.Complete))
      _ <- TestSigil.awaitSettled(convId)
    } yield seen.get()
  }

  "Sigil.currentParticipant" should {

    "default to identity — the persisted (frozen) ActiveOnly roster reaches the wire as-is" in {
      TestSigil.resetCurrentParticipant()
      val convId = Conversation.id(s"reconcile-identity-${rapid.Unique()}")
      runTurnCapturingRoster(convId).map { roster =>
        roster should contain(LookupTool.name)
        // The frozen roster never had `sleep`, and ActiveOnly suppresses
        // find_capability — so it can't appear without reconciliation.
        roster should not contain SleepTool.name
      }
    }

    "deliver a tool added by the app's current definition into an EXISTING conversation" in {
      // Simulate a deploy that added `sleep` to the agent's ActiveOnly roster
      // AFTER this conversation was created with `[lookup]` only.
      TestSigil.setCurrentParticipant {
        case a: AgentParticipant if a.id == TestAgent =>
          Task.pure(agentWith(List(LookupTool.name, SleepTool.name)))
        case other => Task.pure(other)
      }
      val convId = Conversation.id(s"reconcile-grow-${rapid.Unique()}")
      runTurnCapturingRoster(convId).flatMap { roster =>
        TestSigil.withDB(_.conversations.transaction(_.get(convId))).map { stored =>
          TestSigil.resetCurrentParticipant()
          // The newly-added tool reached the wire on the existing conversation.
          roster should contain(SleepTool.name)
          roster should contain(LookupTool.name)
          // Per-turn reconciliation — the persisted participant is untouched.
          val persistedTools = stored.toList.flatMap(_.participants).collect {
            case a: AgentParticipant => a.toolNames
          }.flatten
          persistedTools should contain(LookupTool.name)
          persistedTools should not contain SleepTool.name
        }
      }
    }

    "ignore a reconciliation result whose id differs from the persisted seat" in {
      // An app must not swap the seat's identity; the framework keeps the
      // persisted agent when the returned id doesn't match.
      TestSigil.setCurrentParticipant(_ =>
        Task.pure(agentWith(List(SleepTool.name)) match {
          case a: DefaultAgentParticipant => a.copy(id = StrictAgent)
        }))
      val convId = Conversation.id(s"reconcile-idmismatch-${rapid.Unique()}")
      runTurnCapturingRoster(convId).map { roster =>
        TestSigil.resetCurrentParticipant()
        // Fell back to the persisted `[lookup]` agent — the mismatched-id
        // result (which carried `sleep`) was rejected.
        roster should contain(LookupTool.name)
        roster should not contain SleepTool.name
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
