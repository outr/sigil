package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.participant.DefaultAgentParticipant
import sigil.provider.ToolPolicy
import sigil.tool.ToolName
import sigil.tool.client.ClientToolSpec

/**
 * Registered client tools reach the effective roster WITHOUT a
 * `find_capability` round-trip — including on hosts that suppress
 * discovery entirely. Registration is conversation-scoped explicit
 * intent, so the names join the policy fold as extras (the semantics
 * of an explicit `ToolPolicy.Active` overlay): they survive
 * `ActiveOnly`, `Exclusive`, and `None`, and leave with
 * unregistration.
 */
class ClientToolRosterSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConv(): Id[Conversation] = {
    val id = Conversation.id(s"ctr-${rapid.Unique()}")
    TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(_id = id, topics = List(TestTopicEntry))))).sync()
    id
  }

  private def clientSpec(name: String): ClientToolSpec =
    ClientToolSpec(name = name, description = s"Client surface $name for roster tests.")

  private def agentWith(policy: ToolPolicy): DefaultAgentParticipant =
    DefaultAgentParticipant(id = TestAgent, modelId = TestSigil.defaultTestModel._id, tools = policy)

  private def rosterFor(convId: Id[Conversation], policy: ToolPolicy): Task[List[String]] =
    TestSigil.conversationToolOverlays(convId).map { overlays =>
      TestSigil.effectiveToolNames(
        agentWith(policy),
        sigil.provider.ConversationMode,
        suggested = Nil,
        overlays = overlays.map(_.policy),
        clientToolNames = TestSigil.clientTools.toolsFor(convId).map(_.name)
      ).map(_.value)
    }

  "client tools in the effective roster" should {

    "surface under ActiveOnly with find_capability suppressed (the discovery-off host)" in {
      val convId = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-r1", List(clientSpec("open_widget_editor")))
        names <- rosterFor(convId, ToolPolicy.ActiveOnly(List(ToolName("respond"))))
      } yield {
        names should contain("open_widget_editor")
        names should not contain "find_capability"
      }
    }

    "survive Exclusive and None policies (explicit registration outranks mode lockdown)" in {
      val convId = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-r2", List(clientSpec("open_widget_editor")))
        exclusive <- rosterFor(convId, ToolPolicy.Exclusive(List(ToolName("respond"))))
        none <- rosterFor(convId, ToolPolicy.None)
      } yield {
        exclusive should contain("open_widget_editor")
        none should contain("open_widget_editor")
      }
    }

    "leave the roster on unregistration" in {
      val convId = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-r3", List(clientSpec("open_widget_editor")))
        before <- rosterFor(convId, ToolPolicy.Standard)
        _ <- TestSigil.clientTools.deregisterSession("tab-r3")
        after <- rosterFor(convId, ToolPolicy.Standard)
      } yield {
        before should contain("open_widget_editor")
        after should not contain "open_widget_editor"
      }
    }

    "remain present on discovery-enabled hosts too — always-on, not discovery-gated" in {
      val convId = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-r4", List(clientSpec("open_widget_editor")))
        names <- rosterFor(convId, ToolPolicy.Standard)
        resolved <- TestSigil.resolveToolFor(convId, ToolName("open_widget_editor"))
      } yield {
        names should contain("find_capability")
        names should contain("open_widget_editor")
        resolved should not be empty
      }
    }

    "stay scoped to their own conversation" in {
      val convId = freshConv()
      val otherConv = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-r5", List(clientSpec("open_widget_editor")))
        other <- rosterFor(otherConv, ToolPolicy.Standard)
      } yield other should not contain "open_widget_editor"
    }
  }
}
