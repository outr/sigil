package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.GlobalSpace
import sigil.conversation.{ActiveSkillSlot, Conversation, ParticipantProjection, SkillSource, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{
  CallId, ConversationMode, GenerationSettings, Instructions, Mode, Provider,
  ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.skill.Skill
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Always-on space-scoped skills: a [[Skill]] with `alwaysOn = true` is
 * baseline context for every conversation in its space — included at
 * turn build with no discovery or `activate_skill` step, working even
 * when `find_capability` is absent from the roster. Content is
 * materialized fresh from the record each turn, so registration and
 * edits reach EXISTING conversations on their next iteration.
 */
class AlwaysOnSkillSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "always-on-skill-model")
  TestSigil.testModel(modelId)

  case class TestSkill(name: String,
                       description: String,
                       content: String,
                       override val space: sigil.SpaceId = GlobalSpace,
                       override val modes: Set[Id[Mode]] = Set.empty,
                       override val enabled: Boolean = true,
                       override val alwaysOn: Boolean = false)
    extends Skill derives RW
  Skill.register(summon[RW[TestSkill]])

  private def upsert(skills: TestSkill*): Task[Unit] =
    TestSigil.withDB(_.skills.transaction { tx =>
      skills.toList.foldLeft(Task.unit)((acc, s) => acc.flatMap(_ => tx.upsert(s).unit))
    })

  private def convIn(space: sigil.SpaceId, mode: Mode = ConversationMode): Conversation =
    Conversation(
      topics = TestTopicStack,
      currentMode = mode,
      space = space,
      _id = Conversation.id(s"always-on-${rapid.Unique()}"))

  "alwaysOnSkillsFor" should {

    "include an always-on skill whose space matches the conversation, and GlobalSpace ones everywhere" in {
      for {
        _ <- upsert(
          TestSkill("org-policy", "org baseline", "ORG-POLICY-CONTENT", space = TestSpace, alwaysOn = true),
          TestSkill("global-baseline", "global baseline", "GLOBAL-CONTENT", space = GlobalSpace, alwaysOn = true),
          TestSkill("other-org", "other org's baseline", "OTHER-ORG-CONTENT", space = WiringSpace, alwaysOn = true)
        )
        slots <- TestSigil.alwaysOnSkillsFor(convIn(TestSpace))
      } yield {
        val names = slots.map(_.name).toSet
        names should contain("org-policy")
        names should contain("global-baseline")
        // Another tenant's skill never leaks across spaces.
        names should not contain "other-org"
        slots.find(_.name == "org-policy").map(_.content) shouldBe Some("ORG-POLICY-CONTENT")
      }
    }

    "exclude disabled and non-always-on skills — a plain space-scoped skill stays discovery-driven" in {
      for {
        _ <- upsert(
          TestSkill("disabled-baseline", "off", "DISABLED-CONTENT", space = TestSpace, alwaysOn = true, enabled = false),
          TestSkill("opt-in-skill", "discovery only", "OPT-IN-CONTENT", space = TestSpace, alwaysOn = false)
        )
        slots <- TestSigil.alwaysOnSkillsFor(convIn(TestSpace))
      } yield {
        val names = slots.map(_.name).toSet
        names should not contain "disabled-baseline"
        names should not contain "opt-in-skill"
      }
    }

    "gate by mode: a mode-restricted always-on skill applies only in its modes" in {
      for {
        _ <- upsert(
          TestSkill(
            "coding-baseline",
            "coding-only baseline",
            "CODING-CONTENT",
            space = TestSpace,
            modes = Set(TestCodingMode.id),
            alwaysOn = true)
        )
        inConversation <- TestSigil.alwaysOnSkillsFor(convIn(TestSpace, mode = ConversationMode))
        inCoding <- TestSigil.alwaysOnSkillsFor(convIn(TestSpace, mode = TestCodingMode))
      } yield {
        inConversation.map(_.name) should not contain "coding-baseline"
        inCoding.map(_.name) should contain("coding-baseline")
      }
    }
  }

  "TurnInput.aggregatedSkills" should {

    "union always-on skills with projection slots, deduplicated by name" in Task {
      val convId = Conversation.id(s"always-on-agg-${rapid.Unique()}")
      val projection = ParticipantProjection.empty(TestAgent, convId).copy(
        activeSkills = Map(
          SkillSource.Discovery -> ActiveSkillSlot("org-policy", "ACTIVATED-COPY"),
          SkillSource.Mode -> ActiveSkillSlot("mode-skill", "MODE-CONTENT")
        )
      )
      val turn = TurnInput(
        conversationId = convId,
        participantProjections = Map(TestAgent -> projection),
        alwaysOnSkills = Vector(
          ActiveSkillSlot("org-policy", "ALWAYS-ON-COPY"),
          ActiveSkillSlot("global-baseline", "GLOBAL-CONTENT")
        )
      )
      val skills = turn.aggregatedSkills(List(TestAgent))
      skills.map(_.name) should contain allOf ("org-policy", "mode-skill", "global-baseline")
      // Dedup by name: the explicitly-activated copy wins, rendered once.
      skills.count(_.name == "org-policy") shouldBe 1
    }
  }

  "the wire prompt" should {

    "carry an always-on skill for the conversation's space with NO discovery in the roster, and pick up edits next turn" in {
      val skill = TestSkill("wire-org-policy", "org baseline", "WIRE-BASELINE-V1", space = TestSpace, alwaysOn = true)
      val systems = new ConcurrentLinkedQueue[String]()
      val calls = new atomic.AtomicInteger(0)
      val provider = new Provider {
        override def `type`: ProviderType = ProviderType.LlamaCpp
        override def models: List[Model] = Nil
        override protected def sigil: _root_.sigil.Sigil = TestSigil
        override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
          Task.error(new UnsupportedOperationException("no wire"))
        override def call(input: ProviderCall): Stream[ProviderEvent] = {
          calls.incrementAndGet()
          systems.add(input.system)
          val cid = CallId(s"respond-${rapid.Unique()}")
          Stream.emits(List[ProviderEvent](
            ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
            ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
              topicLabel = TestTopicEntry.label,
              topicSummary = TestTopicEntry.summary,
              content = "Understood.",
              endsTurn = true
            )),
            ProviderEvent.Done(StopReason.Complete)
          ))
        }
      }
      TestSigil.reset()
      TestSigil.setProvider(Task.pure(provider))
      // The widge shape: no find_capability in the roster — always-on
      // skills must not depend on discovery existing at all.
      val agent = DefaultAgentParticipant(
        id = TestAgent,
        modelId = modelId,
        toolNames = CoreTools.coreToolNames,
        tools = sigil.provider.ToolPolicy.None,
        instructions = Instructions(),
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
      )
      val conv = convIn(TestSpace).copy(participants = List(agent))
      def userMsg(text: String) = TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = conv._id,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text(text)),
        state = EventState.Complete
      ))
      def waitForCalls(n: Int): Task[Unit] = {
        val deadline = System.currentTimeMillis() + 15000L
        def loop: Task[Unit] =
          if (calls.get() >= n || System.currentTimeMillis() > deadline) Task.unit
          else Task.sleep(50.millis).flatMap(_ => loop)
        loop
      }
      for {
        _ <- upsert(skill)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- userMsg("Hello.")
        _ <- waitForCalls(1)
        _ <- TestSigil.awaitSettled(conv._id)
        // Edit the skill — the EXISTING conversation must render the
        // new content on its next turn, with no re-activation anywhere.
        _ <- upsert(skill.copy(content = "WIRE-BASELINE-V2"))
        _ <- userMsg("And again.")
        _ <- waitForCalls(2)
        _ <- TestSigil.awaitSettled(conv._id)
      } yield {
        val rendered = systems.iterator().asScala.toList
        rendered.size should be >= 2
        rendered.head should include("WIRE-BASELINE-V1")
        rendered.head should include("wire-org-policy")
        // No stale copy: the edit applied to the same conversation.
        rendered(1) should include("WIRE-BASELINE-V2")
        rendered(1) should not include "WIRE-BASELINE-V1"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
