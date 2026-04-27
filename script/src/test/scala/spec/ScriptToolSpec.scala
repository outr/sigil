package spec

import fabric.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{Conversation, ConversationView, Topic, TopicEntry, TurnInput}
import sigil.event.{Message, ToolResults}
import sigil.participant.{AgentParticipantId, ParticipantId}
import sigil.script.{
  CreateScriptToolInput,
  CreateScriptToolTool,
  DeleteScriptToolInput,
  DeleteScriptToolTool,
  ListScriptToolsInput,
  ListScriptToolsTool,
  ScriptTool,
  UpdateScriptToolInput,
  UpdateScriptToolTool
}
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent

case object TestScriptUser extends ParticipantId {
  override val value: String = "test-script-user"
}

case object TestScriptAgent extends AgentParticipantId {
  override val value: String = "test-script-agent"
}

class ScriptToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestScriptSigil.initFor(getClass.getSimpleName)

  private def ctx(suffix: String, chain: List[ParticipantId] = List(TestScriptUser, TestScriptAgent)): TurnContext = {
    val convId = Conversation.id(s"script-$suffix-${rapid.Unique()}")
    val topic = Topic(conversationId = convId, label = "Scripts", summary = "Test", createdBy = TestScriptUser)
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    TurnContext(
      sigil = TestScriptSigil,
      chain = chain,
      conversation = Conversation(
        topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
        _id = convId
      ),
      conversationView = view,
      turnInput = TurnInput(view)
    )
  }

  private def textOf(events: List[sigil.event.Event]): List[String] =
    events.collect { case m: Message => m }
      .flatMap(_.content.collect { case ResponseContent.Text(t) => t; case ResponseContent.Markdown(t) => t })

  "ScriptTool round-trip" should {
    "persist via Sigil.createTool and read back as a ScriptTool" in {
      val tool = ScriptTool(
        name        = ToolName("rt-add"),
        description = "Returns x + 1.",
        code        = "args(\"x\").asInt + 1",
        parameters  = sigil.tool.JsonSchemaToDefinition(obj(
          "type" -> str("object"),
          "properties" -> obj("x" -> obj("type" -> str("integer")))
        )),
        space       = GlobalSpace
      )
      for {
        _      <- TestScriptSigil.createTool(tool)
        loaded <- TestScriptSigil.withDB(_.tools.transaction(_.get(tool._id)))
      } yield {
        loaded shouldBe defined
        loaded.get shouldBe a[ScriptTool]
        val s = loaded.get.asInstanceOf[ScriptTool]
        s.name shouldBe ToolName("rt-add")
        s.code shouldBe "args(\"x\").asInt + 1"
        s.space shouldBe GlobalSpace
      }
    }
  }

  "CreateScriptToolTool" should {
    "persist a new tool, emit an ack Message and a ToolResults suggestion cascade" in {
      TestScriptSigil.resetSpaceResolver()
      val context = ctx("create")
      val input = CreateScriptToolInput(
        name        = "create-emit-cascade",
        description = "Compute the sum of values.",
        code        = "args(\"values\").asVector.map(_.asDouble).sum.toString",
        parameters  = obj(
          "type" -> str("object"),
          "properties" -> obj("values" -> obj("type" -> str("array"), "items" -> obj("type" -> str("number"))))
        )
      )
      CreateScriptToolTool.execute(input, context).toList.flatMap { events =>
        val messages = events.collect { case m: Message => m }
        val results  = events.collect { case t: ToolResults => t }
        TestScriptSigil.withDB(_.tools.transaction { tx =>
          tx.query.filter(_.toolName === "create-emit-cascade").toList.map(_.headOption)
        }).map { stored =>
          messages should have size 1
          textOf(events).head should include("Persisted tool 'create-emit-cascade'")

          results should have size 1
          val schemas = results.head.schemas.map(_.name.value).toSet
          schemas should contain("create-emit-cascade")
          schemas should contain("update_script_tool")
          schemas should contain("delete_script_tool")

          stored shouldBe defined
          stored.get shouldBe a[ScriptTool]
          stored.get.space shouldBe GlobalSpace
        }
      }
    }

    "honor an app-supplied space resolver (user-scoped pattern)" in {
      TestScriptSigil.setSpaceResolver((_, _) => Task.pure(TestProjectSpace))
      val context = ctx("user-scoped")
      val input = CreateScriptToolInput(
        name = "user-scoped-tool",
        description = "Constant value.",
        code = "\"42\""
      )
      CreateScriptToolTool.execute(input, context).toList.flatMap { _ =>
        TestScriptSigil.withDB(_.tools.transaction { tx =>
          tx.query.filter(_.toolName === "user-scoped-tool").toList.map(_.headOption)
        }).map { stored =>
          TestScriptSigil.resetSpaceResolver()
          stored.get shouldBe a[ScriptTool]
          stored.get.space shouldBe TestProjectSpace
        }
      }
    }
  }

  "UpdateScriptToolTool" should {
    "modify an existing tool when the caller has access" in {
      TestScriptSigil.resetSpaceResolver()
      TestScriptSigil.resetAccessible()
      val context = ctx("update-ok")
      val createInput = CreateScriptToolInput(
        name = "update-target",
        description = "v1",
        code = "\"v1\""
      )
      val updateInput = UpdateScriptToolInput(
        name        = "update-target",
        description = Some("v2"),
        code        = Some("\"v2\"")
      )
      for {
        _ <- CreateScriptToolTool.execute(createInput, context).toList
        events <- UpdateScriptToolTool.execute(updateInput, context).toList
        stored <- TestScriptSigil.withDB(_.tools.transaction { tx =>
          tx.query.filter(_.toolName === "update-target").toList.map(_.headOption)
        })
      } yield {
        textOf(events).head should include("Updated tool")
        stored.get.asInstanceOf[ScriptTool].description shouldBe "v2"
        stored.get.asInstanceOf[ScriptTool].code shouldBe "\"v2\""
        // Cascade: schemas include update + delete + the touched tool's schema.
        val results = events.collect { case t: ToolResults => t }
        results should have size 1
        val names = results.head.schemas.map(_.name.value).toSet
        names should contain allOf ("update-target", "update_script_tool", "delete_script_tool")
      }
    }

    "reject when the tool's space is not in the caller's accessible set" in {
      TestScriptSigil.setSpaceResolver((_, _) => Task.pure(TestProjectSpace))
      TestScriptSigil.setAccessible(_ => Task.pure(Set.empty))
      val context = ctx("update-denied")
      for {
        _ <- CreateScriptToolTool.execute(
               CreateScriptToolInput(name = "denied-tool", description = "secret", code = "\"x\""),
               context
             ).toList
        events <- UpdateScriptToolTool.execute(
                    UpdateScriptToolInput(name = "denied-tool", description = Some("hijacked")),
                    context
                  ).toList
        stored <- TestScriptSigil.withDB(_.tools.transaction { tx =>
          tx.query.filter(_.toolName === "denied-tool").toList.map(_.headOption)
        })
      } yield {
        TestScriptSigil.resetSpaceResolver()
        TestScriptSigil.resetAccessible()
        textOf(events).head should include("not accessible")
        // Original description survives.
        stored.get.asInstanceOf[ScriptTool].description shouldBe "secret"
      }
    }
  }

  "DeleteScriptToolTool" should {
    "remove a tool when the caller has access" in {
      TestScriptSigil.resetSpaceResolver()
      TestScriptSigil.resetAccessible()
      val context = ctx("delete-ok")
      for {
        _ <- CreateScriptToolTool.execute(
               CreateScriptToolInput(name = "delete-me", description = "to be removed", code = "\"x\""),
               context
             ).toList
        before <- TestScriptSigil.withDB(_.tools.transaction { tx =>
          tx.query.filter(_.toolName === "delete-me").toList
        })
        _ <- DeleteScriptToolTool.execute(DeleteScriptToolInput(name = "delete-me"), context).toList
        after <- TestScriptSigil.withDB(_.tools.transaction { tx =>
          tx.query.filter(_.toolName === "delete-me").toList
        })
      } yield {
        before should have size 1
        after shouldBe empty
      }
    }
  }

  "ListScriptToolsTool" should {
    "return tools visible under GlobalSpace + accessible app spaces, filter by nameContains" in {
      TestScriptSigil.resetSpaceResolver()
      TestScriptSigil.setAccessible(_ => Task.pure(Set[SpaceId](TestProjectSpace)))
      val context = ctx("list")
      // Two globally visible tools and one project-scoped one.
      for {
        _ <- CreateScriptToolTool.execute(
               CreateScriptToolInput(name = "list-a-global", description = "a", code = "\"a\""),
               context
             ).toList
        _ <- CreateScriptToolTool.execute(
               CreateScriptToolInput(name = "list-b-global", description = "b", code = "\"b\""),
               context
             ).toList
        _ = TestScriptSigil.setSpaceResolver((_, _) => Task.pure(TestProjectSpace))
        _ <- CreateScriptToolTool.execute(
               CreateScriptToolInput(name = "list-c-project", description = "c", code = "\"c\""),
               context
             ).toList
        _ = TestScriptSigil.resetSpaceResolver()
        listed <- ListScriptToolsTool.execute(ListScriptToolsInput(), context).toList
      } yield {
        TestScriptSigil.resetAccessible()
        val text = textOf(listed).mkString("\n")
        text should include("list-a-global")
        text should include("list-b-global")
        text should include("list-c-project")
      }
    }

    "exclude tools from spaces the caller cannot access" in {
      TestScriptSigil.resetSpaceResolver()
      TestScriptSigil.setSpaceResolver((_, _) => Task.pure(TestProjectSpace))
      TestScriptSigil.setAccessible(_ => Task.pure(Set.empty))
      val context = ctx("list-scoped")
      for {
        _ <- CreateScriptToolTool.execute(
               CreateScriptToolInput(name = "list-hidden", description = "hidden", code = "\"x\""),
               context
             ).toList
        listed <- ListScriptToolsTool.execute(ListScriptToolsInput(nameContains = Some("list-hidden")), context).toList
      } yield {
        TestScriptSigil.resetSpaceResolver()
        TestScriptSigil.resetAccessible()
        val text = textOf(listed).mkString("\n")
        text should not include "list-hidden"
      }
    }
  }
}
