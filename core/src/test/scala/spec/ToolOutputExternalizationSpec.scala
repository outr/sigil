package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.ToolResults
import sigil.storage.StoredFile
import sigil.tool.output.{ToolOutputGetInput, ToolOutputGetResult, ToolOutputGetTool, ToolOutputSearchInput, ToolOutputSearchResult, ToolOutputSearchTool, ToolOutputSummaryInput, ToolOutputSummaryResult, ToolOutputSummaryTool}

/**
 * End-to-end coverage for Bug #9 phases 4 + 5 — the externalization
 * machinery (`Sigil.storeToolOutput`) plus the three core tools
 * (`tool_output_get`, `tool_output_search`, `tool_output_summary`),
 * now emitting typed [[ToolOutputGetResult]] / [[ToolOutputSearchResult]]
 * / [[ToolOutputSummaryResult]] enums via `TypedOutputTool[I, O]`.
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
      turnInput        = TurnInput(ConversationView(conversationId = convId))
    )
  }

  /** Decode the typed payload from a `ToolResults` event back to
    * the tool's Output enum via its registered RW. */
  private def typed[T](events: List[sigil.event.Event])(using rw: RW[T]): T = {
    val tr = events.collectFirst { case t: ToolResults => t }
      .getOrElse(fail("expected a ToolResults event"))
    rw.write(tr.typed.get)
  }

  "Sigil.storeToolOutput → ToolOutput*Tool round-trip" should {
    "store a payload and let the agent fetch it back via tool_output_get" in {
      for {
        stored <- TestSigil.storeToolOutput(TestSpace, payload.getBytes("UTF-8"), "text/plain")
        events <- ToolOutputGetTool.execute(ToolOutputGetInput(outputId = stored._id.value), turnContext()).toList
      } yield {
        typed[ToolOutputGetResult](events) match {
          case ToolOutputGetResult.Found(_, _, size, _, content) =>
            size shouldBe payload.getBytes("UTF-8").length.toLong
            content shouldBe payload
          case other => fail(s"expected Found, got $other")
        }
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
        typed[ToolOutputGetResult](events) match {
          case ToolOutputGetResult.Found(_, _, _, returned, content) =>
            returned shouldBe 50L
            content.length shouldBe 50
          case other => fail(s"expected Found, got $other")
        }
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
        typed[ToolOutputSearchResult](events) match {
          case ToolOutputSearchResult.Found(_, _, _, matches, _) =>
            matches.size shouldBe 1
            matches.head.lineNumber shouldBe 137L
            matches.head.match_ should include("ERROR: budget exceeded")
            // Context lines: the matching line plus 1 above + 1 below.
            matches.head.context.split("\n").length shouldBe 3
          case other => fail(s"expected Found, got $other")
        }
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
        typed[ToolOutputSummaryResult](events) match {
          case ToolOutputSummaryResult.Found(_, contentType, size, lineCount, expiresAt, category) =>
            contentType shouldBe "text/plain"
            size shouldBe payload.getBytes("UTF-8").length.toLong
            category shouldBe "ToolOutput"
            // 199 newlines for a 200-line payload (last line has no trailing newline by construction).
            lineCount shouldBe 199L
            // expiresAt was auto-set to now + toolOutputRetention.
            expiresAt should be (defined)
            expiresAt.get should be > 0L
          case other => fail(s"expected Found, got $other")
        }
      }
    }

    "honor authorization — chain without the file's space gets NotFound" in {
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
        typed[ToolOutputGetResult](events) match {
          case ToolOutputGetResult.NotFound(_, error) =>
            error should include("not found or unauthorized")
          case other => fail(s"expected NotFound, got $other")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
