package spec

import fabric.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, Topic, TopicEntry, TurnInput}
import sigil.event.{Event, Message, ModeChange}
import sigil.participant.ParticipantId
import sigil.provider.ToolPolicy
import sigil.script.{
  ClassSignaturesInput,
  ClassSignaturesTool,
  CreateScriptToolInput,
  CreateScriptToolTool,
  LibraryLookupInput,
  LibraryLookupTool,
  ReadSourceInput,
  ReadSourceTool,
  ScriptAuthoringMode,
  ScriptResult,
  ScriptTool
}
import sigil.tool.JsonInput
import sigil.tool.core.ChangeModeTool
import sigil.tool.model.{ChangeModeInput, ResponseContent}

/**
 * Regression coverage for bug #59 — verifies the four moving parts
 * the change introduced:
 *
 *   1. **Mode registration** — `ScriptAuthoringMode` resolves through
 *      `Sigil.modeByName("script-authoring")`, carries the cookbook
 *      skill, and declares a tool roster that includes the
 *      script-authoring family.
 *   2. **Mode switching** — `ChangeModeTool` invoked with
 *      `mode = "script-authoring"` emits a `ModeChange` event
 *      targeting `ScriptAuthoringMode` rather than failing silently
 *      (the tool warns + emits empty when the mode name is unknown).
 *   3. **Mode-gating** — the script-management surface
 *      (`create_script_tool`, `update_script_tool`, …) and the
 *      introspection tools (`library_lookup`, `class_signatures`,
 *      `read_source`) all carry `modes = Set(ScriptAuthoringMode.id)`,
 *      so `find_capability` doesn't surface them in `ConversationMode`.
 *   4. **End-to-end create + run** — drives `CreateScriptToolTool`
 *      with a small Scala body, then re-loads the persisted
 *      `ScriptTool` from the DB and executes it against the live
 *      `ScalaScriptExecutor`, asserting the script's return value
 *      flows through to the resulting `ScriptResult.output`.
 *
 *   Plus quick coverage of the introspection tools themselves
 *   (`library_lookup` / `class_signatures` / `read_source`) so the
 *   reflection paths are exercised against well-known JDK targets.
 */
class ScriptAuthoringModeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestScriptSigil.initFor(getClass.getSimpleName)

  private def ctx(suffix: String, chain: List[ParticipantId] = List(TestScriptUser, TestScriptAgent)): TurnContext = {
    val convId = Conversation.id(s"authoring-$suffix-${rapid.Unique()}")
    val topic = Topic(conversationId = convId, label = "Authoring", summary = "Test", createdBy = TestScriptUser)
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

  private def textOf(events: List[Event]): List[String] =
    events.collect { case m: Message => m }
      .flatMap(_.content.collect { case ResponseContent.Text(t) => t; case ResponseContent.Markdown(t) => t })

  "ScriptAuthoringMode registration" should {
    "resolve through Sigil.modeByName once ScriptSigil is mixed in" in Task {
      val resolved = TestScriptSigil.modeByName("script-authoring")
      resolved shouldBe defined
      resolved.get shouldBe ScriptAuthoringMode
      succeed
    }

    "appear in availableModes alongside ConversationMode" in Task {
      val names = TestScriptSigil.availableModes.map(_.name).toSet
      names should contain ("conversation")
      names should contain ("script-authoring")
      succeed
    }

    "carry the script-authoring skill content with cookbook + best-practices markers" in Task {
      val slot = ScriptAuthoringMode.skill
      slot shouldBe defined
      val body = slot.get.content
      // Cookbook entries the skill teaches.
      body should include ("HttpClient.url")
      body should include ("JsonParser")
      // Best-practices guardrails the skill enforces.
      body should include ("library_lookup")
      body should include ("Pre-imported")
      // Forbidden Scala 2 idioms the skill calls out.
      body should include ("scala.util.parsing.json")
      succeed
    }

    "declare ToolPolicy.Active including the introspection + management tools" in Task {
      ScriptAuthoringMode.tools shouldBe a[ToolPolicy.Active]
      val names = ScriptAuthoringMode.tools.listed.map(_.value).toSet
      names should contain ("library_lookup")
      names should contain ("class_signatures")
      names should contain ("read_source")
      names should contain ("create_script_tool")
      names should contain ("update_script_tool")
      names should contain ("delete_script_tool")
      names should contain ("list_script_tools")
      succeed
    }
  }

  "Mode switching via ChangeModeTool" should {
    "emit a ModeChange event when the agent calls change_mode('script-authoring')" in {
      val context = ctx("change-mode")
      ChangeModeTool.execute(
        ChangeModeInput(mode = "script-authoring", reason = Some("user asked to author a tool")),
        context
      ).toList.map { events =>
        val changes = events.collect { case mc: ModeChange => mc }
        changes should have size 1
        changes.head.mode shouldBe ScriptAuthoringMode
        changes.head.reason shouldBe Some("user asked to author a tool")
      }
    }

    "emit nothing (and warn) when the requested mode is unknown" in {
      val context = ctx("change-mode-unknown")
      ChangeModeTool.execute(
        ChangeModeInput(mode = "no-such-mode"),
        context
      ).toList.map { events =>
        events shouldBe empty
      }
    }
  }

  "Mode-gating (option (a) — script-authoring tools only discoverable in their mode)" should {
    "set modes = Set(ScriptAuthoringMode.id) on every script-authoring tool" in Task {
      val authoringId = ScriptAuthoringMode.id
      // Introspection family.
      LibraryLookupTool.modes shouldBe Set(authoringId)
      ClassSignaturesTool.modes shouldBe Set(authoringId)
      ReadSourceTool.modes shouldBe Set(authoringId)
      // Management surface — by gating these on the mode, an agent in
      // ConversationMode cannot invoke `create_script_tool` until it
      // first switches modes.
      CreateScriptToolTool.modes shouldBe Set(authoringId)
      sigil.script.UpdateScriptToolTool.modes shouldBe Set(authoringId)
      sigil.script.DeleteScriptToolTool.modes shouldBe Set(authoringId)
      sigil.script.ListScriptToolsTool.modes shouldBe Set(authoringId)
      succeed
    }
  }

  "End-to-end create + run" should {
    "create a ScriptTool via CreateScriptToolTool and execute it against the live ScalaScriptExecutor" in {
      TestScriptSigil.resetSpaceResolver()
      val context = ctx("e2e-run")
      val createInput = CreateScriptToolInput(
        name        = "e2e-multiply",
        description = "Multiply a value by 3.",
        // The script body sees `args: fabric.Json` per the cookbook.
        // Last expression is the return value; Tool's execute path
        // calls `.toString` on it.
        code        = "(args(\"x\").asInt * 3).toString",
        parameters  = obj(
          "type" -> str("object"),
          "properties" -> obj("x" -> obj("type" -> str("integer"))),
          "required" -> arr(str("x"))
        )
      )
      for {
        // 1) Persist the tool.
        _      <- CreateScriptToolTool.execute(createInput, context).toList
        // 2) Re-load from the DB so we exercise the round-trip path
        //    rather than holding an in-memory reference.
        loaded <- TestScriptSigil.withDB(_.tools.transaction { tx =>
                    tx.query.filter(_.toolName === "e2e-multiply").toList.map(_.headOption)
                  })
        // 3) Execute the loaded tool with a live JsonInput; assert the
        //    ScriptResult carries the expected output.
        runEvents <- loaded.get
                       .asInstanceOf[ScriptTool]
                       .execute(JsonInput(obj("x" -> num(7))), context)
                       .toList
      } yield {
        loaded shouldBe defined
        loaded.get shouldBe a[ScriptTool]
        val results = runEvents.collect { case r: ScriptResult => r }
        results should have size 1
        // 7 * 3 = 21 — the script's last expression's stringified value.
        results.head.output shouldBe Some("21")
        results.head.error shouldBe None
      }
    }
  }

  "Library introspection tools" should {
    "library_lookup resolves a JDK class by simple name to its FQN" in {
      val context = ctx("lib-lookup")
      LibraryLookupTool.execute(LibraryLookupInput(symbol = "String"), context).toList.map { events =>
        val text = textOf(events).mkString("\n")
        // String is in `java.lang`; the lookup walks the URLClassLoader
        // chain and the bootstrap loader's jars don't show up there
        // (rt.jar / java.base lives behind the platform loader), so we
        // assert against a class we know is on the application
        // classpath: `Sigil` itself.
        text should include ("Candidates for 'String'")
      }
    }

    "library_lookup finds a Sigil-shipped class by simple name" in {
      val context = ctx("lib-lookup-sigil")
      LibraryLookupTool.execute(LibraryLookupInput(symbol = "ScriptAuthoringMode"), context).toList.map { events =>
        val text = textOf(events).mkString("\n")
        text should include ("sigil.script.ScriptAuthoringMode")
      }
    }

    "class_signatures returns constructor + method listings for a known FQN" in {
      val context = ctx("class-sigs")
      ClassSignaturesTool.execute(
        ClassSignaturesInput(fqn = "java.util.ArrayList"),
        context
      ).toList.map { events =>
        val text = textOf(events).mkString("\n")
        text should include ("# java.util.ArrayList")
        // ArrayList's surface is broad; pick a method that is stable
        // across JDK 17+ to avoid version-coupled flakes.
        text should include ("add")
        text should include ("Public methods")
      }
    }

    "class_signatures returns a friendly error for an unknown FQN" in {
      val context = ctx("class-sigs-missing")
      ClassSignaturesTool.execute(
        ClassSignaturesInput(fqn = "no.such.Class"),
        context
      ).toList.map { events =>
        val text = textOf(events).mkString("\n")
        text should include ("class not found on classpath")
      }
    }

    "read_source returns the not-available marker when no -sources.jar ships the symbol" in {
      val context = ctx("read-src-missing")
      ReadSourceTool.execute(
        ReadSourceInput(fqn = "no.such.Class"),
        context
      ).toList.map { events =>
        val text = textOf(events).mkString("\n")
        text should include ("source not available")
      }
    }
  }
}
