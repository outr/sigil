package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.event.{Event, MessageRole, ToolInvoke, ToolOutcome, ToolResults}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.output.JsonPagedResult

/**
 * Regression coverage for sigil bug #202 — when a paginated tool's
 * `ToolResults` lands with navigable content, the universal
 * navigators (`next_page`, `query_tool_output`) must auto-promote
 * into `ParticipantProjection.suggestedTools` so the agent can
 * actually drill into the returned tree.
 */
class PaginationNavigatorPromotionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Per-test conversation id so projection state from one test
  // doesn't bleed into another (suggestedTools is a per-conv
  // projection; the orchestrator never clears it between separate
  // ToolResults events).
  private def freshConvId(): Id[Conversation] = Conversation.id(s"pagination-nav-${rapid.Unique()}")

  private def setup(convId: Id[Conversation]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
      topics = TestTopicStack,
      participants = List(),
      _id = convId
    )))).unit

  private def publishPaginatedToolResult(convId: Id[Conversation], typedPayload: JsonPagedResult): Task[Unit] = {
    // Tool-role events require an `origin` pointing at the parent
    // ToolInvoke (framework invariant). Publish a synthetic invoke
    // first, then pair the ToolResults to it.
    val invokeId = Event.id()
    val invoke = ToolInvoke(
      toolName = ToolName("paginated_probe"),
      participantId = TestAgent,
      conversationId = convId,
      topicId = TestTopicEntry.id,
      _id = invokeId,
      state = EventState.Complete
    )
    val result = ToolResults(
      schemas = Nil,
      participantId = TestAgent,
      conversationId = convId,
      topicId = TestTopicEntry.id,
      outcome = ToolOutcome.Success,
      typed = Some(summon[RW[JsonPagedResult]].read(typedPayload)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      origin = Some(invokeId)
    )
    TestSigil.publish(invoke).flatMap(_ => TestSigil.publish(result)).unit
  }

  private def suggestedToolsOf(participantId: sigil.participant.ParticipantId, convId: Id[Conversation]): Task[List[String]] =
    TestSigil.projectionFor(participantId, convId).map(_.suggestedTools.map(_.value))

  "Sigil pagination navigator promotion (bug #202)" should {

    "auto-promote next_page + query_tool_output when a ToolResults carries a JsonPagedResult with hasMore = true" in {
      val convId = freshConvId()
      val page = JsonPagedResult(
        items = Nil,
        hasMore = true,
        page = 0,
        pageSize = 50,
        referenceId = "ref-1",
        callId = Id[Event]("call-1")
      )
      for {
        _ <- setup(convId)
        _ <- publishPaginatedToolResult(convId, page)
        names <- suggestedToolsOf(TestAgent, convId)
      } yield {
        names should contain("next_page")
        names should contain("query_tool_output")
      }
    }

    "auto-promote when there are no more pages but child nodeIds exist" in {
      val convId = freshConvId()
      val page = JsonPagedResult(
        items = Nil,
        hasMore = false,
        page = 0,
        pageSize = 50,
        referenceId = "ref-2",
        callId = Id[Event]("call-2"),
        nodeIds = List("node-a", "node-b")
      )
      for {
        _ <- setup(convId)
        _ <- publishPaginatedToolResult(convId, page)
        names <- suggestedToolsOf(TestAgent, convId)
      } yield {
        names should contain("next_page")
        names should contain("query_tool_output")
      }
    }

    "NOT promote when a paginated result fits in one page with no children (nothing to navigate to)" in {
      val convId = freshConvId()
      val page = JsonPagedResult(
        items = Nil,
        hasMore = false,
        page = 0,
        pageSize = 50,
        referenceId = "ref-3",
        callId = Id[Event]("call-3"),
        nodeIds = Nil
      )
      for {
        _ <- setup(convId)
        _ <- publishPaginatedToolResult(convId, page)
        names <- suggestedToolsOf(TestAgent, convId)
      } yield {
        names should not contain "next_page"
        names should not contain "query_tool_output"
      }
    }

    "NOT promote for a ToolResults whose typed payload isn't a JsonPagedResult shape" in {
      import fabric.{obj, str}
      val convId = freshConvId()
      val invokeId = Event.id()
      val invoke = ToolInvoke(
        toolName = ToolName("non_paginated_probe"),
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        _id = invokeId,
        state = EventState.Complete
      )
      val result = ToolResults(
        schemas = Nil,
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        outcome = ToolOutcome.Success,
        typed = Some(obj("result" -> str("ok"))),
        state = EventState.Complete,
        role = MessageRole.Tool,
        origin = Some(invokeId)
      )
      for {
        _ <- setup(convId)
        _ <- TestSigil.publish(invoke)
        _ <- TestSigil.publish(result)
        names <- suggestedToolsOf(TestAgent, convId)
      } yield {
        names should not contain "next_page"
        names should not contain "query_tool_output"
      }
    }
  }

  "Bug #209 — multi-step sequence preservation" should {

    // The exact field repro from the bug: a full
    // find_capability → grep → query_tool_output sequence, with
    // every event going through the same `publish` chokepoint
    // production agents use. If this passes, the framework's
    // projection-update path is structurally correct and any
    // field divergence is downstream (stale JAR, app-side
    // projection override, etc.).
    "preserve find_capability matches across grep AND query_tool_output events emitted via publish" in {
      import sigil.event.{CapabilityResults, ToolOutcome}
      import sigil.tool.discovery.{CapabilityMatch, CapabilityStatus, CapabilityType}
      val convId = freshConvId()
      val discovered = (1 to 50).map(i => s"discovered_tool_$i").toList

      // 50 fake find_capability matches.
      val capMatches = discovered.map(n =>
        CapabilityMatch(
          name = n,
          description = s"fake $n",
          capabilityType = CapabilityType.Tool,
          score = 1.0,
          status = CapabilityStatus.Ready
        ))
      val findInvokeId = Event.id()
      val findInvoke = ToolInvoke(
        toolName = ToolName("find_capability"),
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        _id = findInvokeId,
        state = EventState.Complete
      )
      val capResults = CapabilityResults(
        matches = capMatches,
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        query = "fake query",
        state = EventState.Complete,
        role = MessageRole.Tool,
        origin = Some(findInvokeId)
      )

      def pagedInvokeAndResult(toolName: String, ref: String): (ToolInvoke, ToolResults) = {
        val invokeId = Event.id()
        val invoke = ToolInvoke(
          toolName = ToolName(toolName),
          participantId = TestAgent,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          _id = invokeId,
          state = EventState.Complete
        )
        val page = JsonPagedResult(
          items = Nil,
          hasMore = true,
          page = 0,
          pageSize = 100,
          referenceId = ref,
          callId = Id[Event](s"$ref-call")
        )
        val result = ToolResults(
          schemas = Nil,
          participantId = TestAgent,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          outcome = ToolOutcome.Success,
          typed = Some(summon[RW[JsonPagedResult]].read(page)),
          state = EventState.Complete,
          role = MessageRole.Tool,
          origin = Some(invokeId)
        )
        (invoke, result)
      }

      val (grepInvoke, grepResult) = pagedInvokeAndResult("grep", "grep-ref")
      val (qtoInvoke, qtoResult) = pagedInvokeAndResult("query_tool_output", "qto-ref")

      for {
        _ <- setup(convId)
        // 1. find_capability lands → 50 suggestedTools.
        _ <- TestSigil.publish(findInvoke)
        _ <- TestSigil.publish(capResults)
        afterFind <- suggestedToolsOf(TestAgent, convId)
        // 2. grep lands (PaginatedTool shape, schemas=Nil).
        _ <- TestSigil.publish(grepInvoke)
        _ <- TestSigil.publish(grepResult)
        afterGrep <- suggestedToolsOf(TestAgent, convId)
        // 3. query_tool_output lands (TypedOutputTool shape, schemas=Nil).
        _ <- TestSigil.publish(qtoInvoke)
        _ <- TestSigil.publish(qtoResult)
        afterQto <- suggestedToolsOf(TestAgent, convId)
      } yield {
        // After find_capability: exactly the 50 discovered tools.
        afterFind.size shouldBe 50
        discovered.foreach(t => afterFind should contain(t))

        // After grep: the 50 PLUS the 2 navigators (52 total).
        afterGrep.size shouldBe 52
        discovered.foreach(t => afterGrep should contain(t))
        afterGrep should contain("next_page")
        afterGrep should contain("query_tool_output")

        // After query_tool_output (the field-repro failure point):
        // STILL 52 — the original 50 PLUS the navigators, distinct
        // keeps them from doubling.
        afterQto.size shouldBe 52
        discovered.foreach(t => afterQto should contain(t))
        afterQto should contain("next_page")
        afterQto should contain("query_tool_output")
      }
    }

    "preserve a pre-seeded find_capability promotion across grep AND query_tool_output in sequence" in {
      val convId = freshConvId()
      val seeded = (1 to 10).map(i => ToolName(s"discovered_$i")).toList
      val preSeed = TestSigil.withDB(_.participantProjections.transaction { tx =>
        tx.upsert(sigil.conversation.ParticipantProjection.empty(TestAgent, convId)
          .copy(suggestedTools = seeded))
      }).unit

      val grepPage = JsonPagedResult(
        items = Nil,
        hasMore = true,
        page = 0,
        pageSize = 100,
        referenceId = "grep-ref",
        callId = Id[Event]("grep-call")
      )
      val qtoPage = JsonPagedResult(
        items = Nil,
        hasMore = true,
        page = 0,
        pageSize = 100,
        referenceId = "qto-ref",
        callId = Id[Event]("qto-call")
      )

      for {
        _ <- setup(convId)
        _ <- preSeed
        _ <- publishPaginatedToolResult(convId, grepPage)
        afterGrep <- suggestedToolsOf(TestAgent, convId)
        _ <- publishPaginatedToolResult(convId, qtoPage)
        afterQto <- suggestedToolsOf(TestAgent, convId)
      } yield {
        // After grep: original 10 preserved + 2 navigators.
        afterGrep.size should be >= 12
        seeded.foreach(t => afterGrep should contain(t.value))
        afterGrep should contain("next_page")
        afterGrep should contain("query_tool_output")

        // After query_tool_output: original 10 STILL preserved +
        // 2 navigators (distinct keeps them from doubling).
        afterQto.size should be >= 12
        seeded.foreach(t => afterQto should contain(t.value))
        afterQto should contain("next_page")
        afterQto should contain("query_tool_output")
      }
    }
  }

  "PaginatedTool description footer" should {

    "auto-append the standardized navigation footer to every concrete subclass" in {
      val grep = new sigil.tool.fs.GrepTool(new sigil.tool.fs.LocalFileSystemContext)
      val glob = new sigil.tool.fs.GlobTool(new sigil.tool.fs.LocalFileSystemContext)
      val bash = new sigil.tool.fs.BashTool(new sigil.tool.fs.LocalFileSystemContext)
      grep.description should include("next_page")
      grep.description should include("query_tool_output")
      glob.description should include("next_page")
      bash.description should include("next_page")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
