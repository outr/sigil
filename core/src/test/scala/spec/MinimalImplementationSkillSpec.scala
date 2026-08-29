package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.role.Role
import sigil.signal.EventState
import sigil.skill.MinimalImplementationSkill
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * The shipped minimal-implementation skill reaches the wire through
 * the standard Role-skill machinery: an agent whose role carries the
 * slot renders it in the system prompt's active-skills section — the
 * same path `delegate_task` workers inherit via their role or the
 * spawning conversation's mode.
 */
class MinimalImplementationSkillSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "minimal-skill")
  TestSigil.testModel(modelId)

  private final class SystemCapturingProvider(seen: AtomicReference[String]) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      seen.set(input.system)
      Stream.emits(List(
        ProviderEvent.ContentBlockDelta(CallId("min-skill-0"), "done"),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  "the minimal-implementation skill" should {
    "render into the system prompt through a role's skill slot" in {
      val seen = new AtomicReference[String]("")
      TestSigil.setProvider(Task.pure(new SystemCapturingProvider(seen)))
      val implementer = Role(
        name = "implementer",
        description = "Implement the requested code change.",
        skill = Some(MinimalImplementationSkill.slot)
      )
      val agent = DefaultAgentParticipant(
        id = TestAgent,
        modelId = modelId,
        toolNames = CoreTools.coreToolNames,
        instructions = Instructions(),
        generationSettings = GenerationSettings(),
        roles = List(implementer)
      )
      val convId = Conversation.id(s"min-skill-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("Add a date picker to the form.")),
               state          = EventState.Complete))
        _ <- waitFor(15.seconds)(seen.get().nonEmpty)
      } yield {
        val system = seen.get()
        system should include (MinimalImplementationSkill.name)
        system should include ("stop at the FIRST rung that holds")
        system should include ("NEVER simplify away")
      }
    }
  }

  private def waitFor(timeout: FiniteDuration)(cond: => Boolean): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] =
      if (cond || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    loop
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.reset()
      TestSigil.shutdown.map(_ => succeed)
    }
  }
}
