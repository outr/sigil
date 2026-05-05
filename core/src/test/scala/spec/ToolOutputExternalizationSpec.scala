package spec

import fabric.io.JsonParser
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Message
import sigil.storage.StoredFile
import sigil.tool.model.ResponseContent
import sigil.tool.output.{ToolOutputGetInput, ToolOutputGetTool, ToolOutputSearchInput, ToolOutputSearchTool, ToolOutputSummaryInput, ToolOutputSummaryTool}

/**
 * End-to-end coverage for Bug #9 phases 4 + 5 — the externalization
 * machinery (`Sigil.storeToolOutput`) plus the three core tools
 * (`tool_output_get`, `tool_output_search`, `tool_output_summary`).
 *
 * Drives the full flow from a tool author's perspective:
 *   1. A simulated tool produces a 5 KB payload (above the
 *      externalization threshold).
 *   2. The tool calls `sigil.storeToolOutput` and gets a StoredFile id.
 *   3. The agent calls each `tool_output_*` tool with that id and
 *      asserts the metadata, slice, and grep paths all return the
 *      expected payload.
 *   4. Authorization is honored — a chain without the file's space
 *      gets `ok = false`.
 */
class ToolOutputExternalizationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setAccessibleSpaces(_ => Task.pure(Set[sigil.SpaceId](TestSpace)))

  private val convId = Conversation.id(s"tool-output-${rapid.Unique()}")

  /** Multi-line payload large enough to exceed any reasonable
    * externalization threshold. Carries a known marker the search
    * test grep on. */
  private val payload: String = (1 to 200)
    .map(i => s"line $i: " + (if (i == 137) "ERROR: budget exceeded for partition 7" else "ok"))
    .mkString("\n")

  private def turnContext(chain: List[sigil.participant.ParticipantId] = List(TestUser)): TurnContext = {
    val conv = Conversation(
      topics       = List(TopicEntry(TestTopicId, "test", "test")),
      participants = Nil,
      _id          = convId
    )
    TurnContext(
      sigil            = TestSigil,
      chain            = chain,
      conversation     = conv,
      conversationView = ConversationView(conversationId = convId),
      turnInput        = TurnInput(ConversationView(conversationId = convId))
    )
  }

  private def extractJson(events: List[sigil.event.Event]): fabric.Json =
    events.collectFirst { case m: Message =>
      m.content.collectFirst { case ResponseContent.Text(t) => t }
    }.flatten.map(JsonParser(_)).getOrElse(fabric.Obj.empty)

  "Sigil.storeToolOutput → ToolOutput*Tool round-trip" should {
    "store a payload and let the agent fetch it back via tool_output_get" in {
      for {
        stored <- TestSigil.storeToolOutput(TestSpace, payload.getBytes("UTF-8"), "text/plain")
        events <- ToolOutputGetTool.execute(ToolOutputGetInput(outputId = stored._id.value), turnContext()).toList
      } yield {
        val json = extractJson(events)
        json.get("ok").map(_.asString) shouldBe Some("true")
        json.get("size").map(_.asLong) shouldBe Some(payload.getBytes("UTF-8").length.toLong)
        json.get("content").map(_.asString) shouldBe Some(payload)
      }
    }

    "return only a byte slice when start + length are set" in {
      for {
        stored <- TestSigil.storeToolOutput(TestSpace, payload.getBytes("UTF-8"), "text/plain")
        events <- ToolOutputGetTool.execute(
          ToolOutputGetInput(outputId = stored._id.value, start = Some(0), length = Some(50)),
          turnContext()
        ).toList
      } yield {
        val json = extractJson(events)
        json.get("ok").map(_.asString) shouldBe Some("true")
        json.get("returned").map(_.asLong) shouldBe Some(50L)
        json.get("content").map(_.asString).get.length shouldBe 50
      }
    }

    "find matching lines via tool_output_search and return them with context" in {
      for {
        stored <- TestSigil.storeToolOutput(TestSpace, payload.getBytes("UTF-8"), "text/plain")
        events <- ToolOutputSearchTool.execute(
          ToolOutputSearchInput(outputId = stored._id.value, pattern = "ERROR", contextLines = Some(1)),
          turnContext()
        ).toList
      } yield {
        val json = extractJson(events)
        json.get("ok").map(_.asString) shouldBe Some("true")
        val matches = json.get("matches").map(_.asVector).getOrElse(Vector.empty)
        matches.size shouldBe 1
        matches.head.get("lineNumber").map(_.asLong) shouldBe Some(137L)
        matches.head.get("match").map(_.asString).get should include("ERROR: budget exceeded")
        // Context lines: the matching line plus 1 above + 1 below.
        matches.head.get("context").map(_.asString).get.split("\n").length shouldBe 3
      }
    }

    "return metadata via tool_output_summary without loading the bytes inline" in {
      for {
        stored <- TestSigil.storeToolOutput(TestSpace, payload.getBytes("UTF-8"), "text/plain")
        events <- ToolOutputSummaryTool.execute(
          ToolOutputSummaryInput(outputId = stored._id.value),
          turnContext()
        ).toList
      } yield {
        val json = extractJson(events)
        json.get("ok").map(_.asString) shouldBe Some("true")
        json.get("contentType").map(_.asString) shouldBe Some("text/plain")
        json.get("size").map(_.asLong) shouldBe Some(payload.getBytes("UTF-8").length.toLong)
        json.get("category").map(_.asString) shouldBe Some("ToolOutput")
        // 199 newlines for a 200-line payload (last line has no
        // trailing newline by construction).
        json.get("lineCount").map(_.asLong) shouldBe Some(199L)
        // expiresAt was auto-set to now + toolOutputRetention so
        // it should be a non-empty positive numeric string.
        val expires = json.get("expiresAt").map(_.asString).get
        expires should not be "never"
        expires.toLong should be > 0L
      }
    }

    "honor authorization — chain without the file's space gets `ok = false`" in {
      val unauthorized = new sigil.participant.ParticipantId {
        override def value: String = "unauthorized"
      }
      // Override accessibleSpaces just for this scenario so the
      // unauthorized id resolves to no spaces.
      TestSigil.setAccessibleSpaces { chain =>
        if (chain.exists(_.value == "unauthorized")) Task.pure(Set.empty[sigil.SpaceId])
        else Task.pure(Set[sigil.SpaceId](TestSpace))
      }
      for {
        stored <- TestSigil.storeToolOutput(TestSpace, payload.getBytes("UTF-8"), "text/plain")
        events <- ToolOutputGetTool.execute(
          ToolOutputGetInput(outputId = stored._id.value),
          turnContext(chain = List(unauthorized))
        ).toList
      } yield {
        val json = extractJson(events)
        json.get("ok").map(_.asString) shouldBe Some("false")
        json.get("error").map(_.asString).get should include("not found or unauthorized")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
