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
 * Dual-path client-tool surfacing. Discovery-enabled hosts reach
 * registered client tools through `find_capability` (they join the
 * discovery catalog for their conversation); hosts whose policy fold
 * leaves no discovery path get them injected into the roster directly.
 * Either way, an explicit conversation-scoped registration is
 * reachable — and leaves with unregistration.
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

    "inject under None (no discovery path) but defer to discovery under Exclusive (find_capability retained)" in {
      val convId = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-r2", List(clientSpec("open_widget_editor")))
        exclusive <- rosterFor(convId, ToolPolicy.Exclusive(List(ToolName("respond"))))
        none <- rosterFor(convId, ToolPolicy.None)
      } yield {
        exclusive should contain("find_capability")
        exclusive should not contain "open_widget_editor"
        none should contain("open_widget_editor")
      }
    }

    "leave the roster on unregistration" in {
      val convId = freshConv()
      val policy = ToolPolicy.ActiveOnly(List(ToolName("respond")))
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-r3", List(clientSpec("open_widget_editor")))
        before <- rosterFor(convId, policy)
        _ <- TestSigil.clientTools.deregisterSession("tab-r3")
        after <- rosterFor(convId, policy)
      } yield {
        before should contain("open_widget_editor")
        after should not contain "open_widget_editor"
      }
    }

    "stay discovery-gated on discovery-enabled hosts — findable and resolvable, not pre-injected" in {
      val convId = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-r4", List(clientSpec("open_widget_editor")))
        names <- rosterFor(convId, ToolPolicy.Standard)
        found <- TestSigil.findCapabilities(sigil.tool.DiscoveryRequest(
          keywords = "open widget editor",
          chain = List(TestUser, TestAgent),
          mode = sigil.provider.ConversationMode,
          callerSpaces = Set.empty,
          conversationId = Some(convId)
        ))
        resolved <- TestSigil.resolveToolFor(convId, ToolName("open_widget_editor"))
      } yield {
        names should contain("find_capability")
        names should not contain "open_widget_editor"
        found.map(_.name) should contain("open_widget_editor")
        resolved should not be empty
      }
    }

    "stay scoped to their own conversation" in {
      val convId = freshConv()
      val otherConv = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-r5", List(clientSpec("open_widget_editor")))
        other <- rosterFor(otherConv, ToolPolicy.ActiveOnly(List(ToolName("respond"))))
      } yield other should not contain "open_widget_editor"
    }
  }
}
