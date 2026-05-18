package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, TurnContext}
import sigil.conversation.{Conversation, Topic, TopicEntry, TurnInput}
import sigil.provider.{ConversationMode, ToolPolicy}
import sigil.script.{CreateScriptToolInput, CreateScriptToolTool}
import sigil.tool.{DiscoveryRequest, ToolName}

/**
 * Coverage for the just-created-script-tool flow:
 *
 *   - `create_script_tool` installs a `ConversationToolOverlay`
 *     for the new tool name so the agent's next turn's effective
 *     roster includes it directly (no `find_capability`
 *     round-trip needed).
 *   - Newly-created `ScriptTool`s default to empty `modes` so
 *     they're discoverable in any mode.
 *   - `findCapabilities` ranks an exact-name query against the
 *     literal tool name above any sibling tool whose description
 *     happens to share more terms.
 */
class CreateScriptToolImmediateInvocationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestScriptSigil.initFor(getClass.getSimpleName)
  TestScriptSigil.setSpaceResolver((_, _) => Task.pure(GlobalSpace))
  // Use a DB-backed tool finder so persisted ScriptTools are
  // discoverable via findCapabilities. The default InMemoryToolFinder
  // returns Nil and the ranking-side tests would never see the
  // just-created tool.
  TestScriptSigil.setToolFinder(sigil.tool.DbToolFinder(
    sigil = TestScriptSigil,
    toolInputRWs = Nil
  ))

  private val convId = Conversation.id(s"create-script-${rapid.Unique()}")
  private val topic = Topic(
    conversationId = convId,
    label = "spec",
    summary = "spec",
    createdBy = TestScriptUser
  )
  private val conv = Conversation(topics = List(TopicEntry(topic._id, topic.label, topic.summary)), _id = convId)

  private def ctx(currentMode: sigil.provider.Mode = sigil.script.ScriptAuthoringMode): TurnContext =
    TurnContext(
      sigil = TestScriptSigil,
      chain = List(TestScriptUser, TestScriptAgent),
      conversation = conv.copy(currentMode = currentMode),
      turnInput = TurnInput(conversationId = convId)
    )

  private def createTool(name: String): Task[Unit] =
    CreateScriptToolTool.execute(
      CreateScriptToolInput(
        name = name,
        description = s"Stub script tool $name",
        code = "args",
        parameters = fabric.obj("type" -> fabric.str("object"), "properties" -> fabric.obj()),
        keywords = Set.empty,
        space = None
      ),
      ctx()
    ).toList.map(_ => ())

  "create_script_tool" should {

    "install a ConversationToolOverlay pinning the new tool to the conversation" in {
      val toolName = s"my_special_tool_${rapid.Unique()}"
      for {
        _ <- TestScriptSigil.withDB(_.conversations.transaction(_.upsert(conv))).unit
        _ <- TestScriptSigil.withDB(_.topics.transaction(_.upsert(topic))).unit
        _ <- createTool(toolName)
        overlays <- TestScriptSigil.conversationToolOverlays(convId)
      } yield {
        val mine = overlays.filter(_.source == s"create_script_tool:$toolName")
        mine should have size 1
        mine.head.policy match {
          case ToolPolicy.Active(names) => names should contain(ToolName(toolName))
          case other => fail(s"expected Active(...) overlay, got: $other")
        }
      }
    }

    "rank the just-created tool first when searched by exact name in the same conversation" in {
      val toolName = s"my_unique_tool_${rapid.Unique()}"
      val accessibleSpaces = Set[sigil.SpaceId](GlobalSpace)
      for {
        _ <- TestScriptSigil.withDB(_.conversations.transaction(_.upsert(conv))).unit
        _ <- TestScriptSigil.withDB(_.topics.transaction(_.upsert(topic))).unit
        _ <- createTool(toolName)
        matches <- TestScriptSigil.findCapabilities(DiscoveryRequest(
          keywords = toolName,
          chain = List(TestScriptUser, TestScriptAgent),
          mode = ConversationMode,
          callerSpaces = accessibleSpaces,
          conversationId = Some(convId)
        ))
      } yield {
        val toolMatches = matches.filter(_.capabilityType.toString.toLowerCase.contains("tool"))
        toolMatches should not be empty
        toolMatches.head.name shouldBe toolName
      }
    }
  }

  "tear down" should {
    "dispose TestScriptSigil" in TestScriptSigil.shutdown.map(_ => succeed)
  }
}
