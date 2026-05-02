package spec

import fabric.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{Conversation, ConversationView, Topic, TopicEntry, TurnInput}
import sigil.event.Message
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

  "ScriptSigil polymorphic registrations (bug #53)" should {
    "register JsonInput so ToolInvoke events for ScriptTool calls round-trip via the ToolInput poly RW" in Task {
      // Concretely — once `ScriptSigil` is mixed in, `RW[ToolInput]`
      // must resolve `JsonInput` to the JsonInput subtype rather than
      // throwing `Type not found [JsonInput]` at persistence. The
      // failing call shape used to be:
      //   - agent calls ScriptTool with `input = JsonInput(args)`
      //   - orchestrator emits `ToolInvoke(input = Some(JsonInput(...)))`
      //   - lightdb persists via fabric `RW[ToolInput]`
      //   - poly dispatch can't find JsonInput → throw → agent loop dies
      // Round-trip via the poly RW directly is the cheapest, deterministic
      // proof that the registration covers it.
      import fabric.rw.RW
      import sigil.tool.{JsonInput, ToolInput}
      val original: ToolInput = JsonInput(fabric.obj("k" -> fabric.str("v")))
      val rw                  = summon[RW[ToolInput]]
      val json                = rw.read(original)
      val roundTripped        = rw.write(json)
      // Pre-fix: the line above threw `Type not found [JsonInput]`.
      // Post-fix: dispatch resolves and we get a JsonInput back. We
      // don't pin the inner json shape exactly — `JsonWrapper`
      // round-trip preserves the type discriminator alongside the
      // original keys — but the original key IS in the result.
      roundTripped shouldBe a[JsonInput]
      roundTripped.asInstanceOf[JsonInput].json("k").asString shouldBe "v"
      succeed
    }
  }

  "ScalaScriptExecutor advertised surface (bug #54)" should {
    "expose a non-empty preludeImports list" in Task {
      import sigil.script.ScalaScriptExecutor
      val exec = new ScalaScriptExecutor
      exec.preludeImports should not be empty
      // Specific identifiers the framework ships and that LLMs need
      // to know are pre-imported (avoids them reaching for the Scala
      // 2 stdlib equivalents).
      exec.preludeImports.mkString("|") should include("fabric")
      exec.preludeImports.mkString("|") should include("spice.http.client.HttpClient")
      exec.preludeImports.mkString("|") should include("rapid.Task")
      succeed
    }

    "advertise the surface in CreateScriptToolTool's descriptionFor" in Task {
      val rendered = sigil.script.CreateScriptToolTool.descriptionFor(
        mode          = sigil.provider.ConversationMode,
        sigilInstance = TestScriptSigil
      )
      rendered should include("Pre-imported")
      rendered should include("HttpClient")
      rendered should include("scala.util.parsing.json")  // the "avoid" callout
      succeed
    }
  }

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

  "ScriptTool id (bug #48)" should {
    "overwrite in place when an agent re-creates a tool with the same name + space" in {
      TestScriptSigil.resetSpaceResolver()
      val context = ctx("collide-overwrite")
      val first = CreateScriptToolInput(
        name        = "collision-target",
        description = "v1",
        code        = "\"v1\""
      )
      val second = first.copy(description = "v2", code = "\"v2\"")
      for {
        _      <- CreateScriptToolTool.execute(first, context).toList
        _      <- CreateScriptToolTool.execute(second, context).toList
        stored <- TestScriptSigil.withDB(_.tools.transaction { tx =>
                    tx.query.filter(_.toolName === "collision-target").toList
                  })
      } yield {
        // Single row — second create overwrote the first via the
        // (name, space)-derived `_id`.
        stored should have size 1
        val s = stored.head.asInstanceOf[ScriptTool]
        s.code shouldBe "\"v2\""
        s.description shouldBe "v2"
      }
    }

    "keep separate rows when the same name lands in different spaces" in {
      // Same tool name, two different spaces, two rows survive.
      TestScriptSigil.resetSpaceResolver()
      val globalContext = ctx("collide-global")
      val projContext   = ctx("collide-proj")
      for {
        _ <- CreateScriptToolTool.execute(
               CreateScriptToolInput(name = "across-spaces", description = "global", code = "\"g\""),
               globalContext
             ).toList
        _ = TestScriptSigil.setSpaceResolver((_, _) => Task.pure(TestProjectSpace))
        _ <- CreateScriptToolTool.execute(
               CreateScriptToolInput(name = "across-spaces", description = "project", code = "\"p\""),
               projContext
             ).toList
        _ = TestScriptSigil.resetSpaceResolver()
        rows <- TestScriptSigil.withDB(_.tools.transaction { tx =>
                  tx.query.filter(_.toolName === "across-spaces").toList
                })
      } yield {
        rows should have size 2
        rows.collect { case s: ScriptTool => s.space }.toSet shouldBe Set[SpaceId](GlobalSpace, TestProjectSpace)
      }
    }
  }

  "CreateScriptToolTool" should {
    "persist a new tool, emit a single Message(Tool) carrying confirmation + schema, and auto-pop to ConversationMode" in {
      // Bugs #68 / #69 — replaces the previous [ack, ToolResults] two-event
      // cascade. Now: ONE Message(Tool) with the full info inline + a
      // ModeChange(Standard) that auto-pops the agent back to
      // ConversationMode so the tool's `modes = Set(ConversationMode.id)`
      // matches the next find_capability's mode-affinity filter.
      TestScriptSigil.resetSpaceResolver()
      val context = ctx("create")
      val input = CreateScriptToolInput(
        name        = "create-single-result",
        description = "Compute the sum of values.",
        code        = "args(\"values\").asVector.map(_.asDouble).sum.toString",
        parameters  = obj(
          "type" -> str("object"),
          "properties" -> obj("values" -> obj("type" -> str("array"), "items" -> obj("type" -> str("number"))))
        )
      )
      CreateScriptToolTool.execute(input, context).toList.flatMap { events =>
        val toolMessages = events.collect { case m: Message if m.role == sigil.event.MessageRole.Tool => m }
        val modeChanges  = events.collect { case mc: sigil.event.ModeChange => mc }
        TestScriptSigil.withDB(_.tools.transaction { tx =>
          tx.query.filter(_.toolName === "create-single-result").toList.map(_.headOption)
        }).map { stored =>
          // Exactly one MessageRole.Tool event — pairs cleanly with the
          // create_script_tool call_id; no orphan-frame fall-through.
          toolMessages should have size 1
          val text = textOf(toolMessages).head
          text should include ("Persisted tool 'create-single-result'")
          // Schema + invocation hint inline.
          text should include ("To invoke")
          text should include ("create-single-result")
          // ModeChange auto-pop to ConversationMode (Standard role —
          // doesn't compete with the ack for the tool-result frame).
          modeChanges should have size 1
          modeChanges.head.mode shouldBe sigil.provider.ConversationMode
          modeChanges.head.role shouldBe sigil.event.MessageRole.Standard

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
        // Bug #69 — exactly one Message(Tool) carrying the
        // confirmation + the (possibly-updated) schema.
        val toolMessages = events.collect { case m: Message if m.role == sigil.event.MessageRole.Tool => m }
        toolMessages should have size 1
        val text = textOf(toolMessages).head
        text should include ("Updated tool 'update-target'")
        text should include ("Current invocation shape")
        stored.get.asInstanceOf[ScriptTool].description shouldBe "v2"
        stored.get.asInstanceOf[ScriptTool].code shouldBe "\"v2\""
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
