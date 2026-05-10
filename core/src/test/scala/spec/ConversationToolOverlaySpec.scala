package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, ConversationToolOverlay}
import sigil.event.Event
import sigil.participant.AgentParticipant
import sigil.provider.{ConversationMode, ToolPolicy}
import sigil.role.GeneralistRole
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.TurnContext

/**
 * Coverage for sigil bug #97 — `ConversationToolOverlay` lets tools
 * like `start_metals` pin a tool family as `Active(...)` on a
 * conversation so subsequent turns reach those tools without a
 * `find_capability` round-trip.
 *
 * Verifies persistence, idempotent install, symmetric remove, and
 * the additive fold into `effectiveToolNames`.
 */
class ConversationToolOverlaySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  case class StubInput(text: String = "") extends ToolInput derives RW

  case object PinnedToolA extends TypedTool[StubInput](
    name        = ToolName("pinned_tool_a"),
    description = "Stub pinned by overlay"
  ) {
    override protected def executeTyped(input: StubInput, context: TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
  }

  case object PinnedToolB extends TypedTool[StubInput](
    name        = ToolName("pinned_tool_b"),
    description = "Stub pinned by overlay"
  ) {
    override protected def executeTyped(input: StubInput, context: TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
  }

  ToolInput.register(RW.static(StubInput("")))

  /** Minimal AgentParticipant — only the abstract members. */
  private case object FoldAgent extends AgentParticipant {
    override def id: sigil.participant.AgentParticipantId = TestAgent
    override def modelId: Id[sigil.db.Model]               = sigil.db.Model.id("test", "overlay-spec")
    override def roles: List[sigil.role.Role]              = List(GeneralistRole)
    override def displayName: String                       = "FoldAgent"
    override def avatarUrl: Option[String]                 = None
  }

  private val convId = Conversation.id(s"overlay-conv-${rapid.Unique()}")

  "ConversationToolOverlay (#97)" should {

    "persist via addConversationToolOverlay and read back via conversationToolOverlays" in {
      val overlay = ConversationToolOverlay(
        conversationId = convId,
        source         = "test-source",
        policy         = ToolPolicy.Active(List(PinnedToolA.name, PinnedToolB.name))
      )
      for {
        _    <- TestSigil.addConversationToolOverlay(overlay)
        list <- TestSigil.conversationToolOverlays(convId)
      } yield {
        list.map(_.source) should contain("test-source")
        list.find(_.source == "test-source").map(_.policy) shouldBe Some(
          ToolPolicy.Active(List(PinnedToolA.name, PinnedToolB.name))
        )
      }
    }

    "fold overlay policies into effectiveToolNames additively" in {
      val overlayPolicy: ToolPolicy = ToolPolicy.Active(List(PinnedToolA.name))
      val names = TestSigil.effectiveToolNames(
        agent     = FoldAgent,
        mode      = ConversationMode,
        suggested = Nil,
        overlays  = List(overlayPolicy)
      )
      // Without the overlay this same call returns the framework
      // essentials only — pinned_tool_a is the overlay's contribution.
      names should contain (PinnedToolA.name)
    }

    "be idempotent on repeated install (same source replaces in place)" in {
      val first = ConversationToolOverlay(
        conversationId = convId,
        source         = "idempotent",
        policy         = ToolPolicy.Active(List(PinnedToolA.name))
      )
      val second = ConversationToolOverlay(
        conversationId = convId,
        source         = "idempotent",
        policy         = ToolPolicy.Active(List(PinnedToolB.name))
      )
      for {
        _    <- TestSigil.addConversationToolOverlay(first)
        _    <- TestSigil.addConversationToolOverlay(second)
        list <- TestSigil.conversationToolOverlays(convId)
      } yield {
        val byThisSource = list.filter(_.source == "idempotent")
        byThisSource should have size 1
        byThisSource.head.policy shouldBe ToolPolicy.Active(List(PinnedToolB.name))
      }
    }

    "drop the record on removeConversationToolOverlay" in {
      val overlay = ConversationToolOverlay(
        conversationId = convId,
        source         = "to-remove",
        policy         = ToolPolicy.Active(List(PinnedToolA.name))
      )
      for {
        _      <- TestSigil.addConversationToolOverlay(overlay)
        before <- TestSigil.conversationToolOverlays(convId)
        _      <- TestSigil.removeConversationToolOverlay(convId, "to-remove")
        after  <- TestSigil.conversationToolOverlays(convId)
      } yield {
        before.exists(_.source == "to-remove") shouldBe true
        after.exists(_.source == "to-remove")  shouldBe false
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
