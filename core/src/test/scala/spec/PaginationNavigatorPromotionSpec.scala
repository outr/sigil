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
      toolName       = ToolName("paginated_probe"),
      participantId  = TestAgent,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      _id            = invokeId,
      state          = EventState.Complete
    )
    val result = ToolResults(
      schemas        = Nil,
      participantId  = TestAgent,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      outcome        = ToolOutcome.Success,
      typed          = Some(summon[RW[JsonPagedResult]].read(typedPayload)),
      state          = EventState.Complete,
      role           = MessageRole.Tool,
      origin         = Some(invokeId)
    )
    TestSigil.publish(invoke).flatMap(_ => TestSigil.publish(result)).unit
  }

  private def suggestedToolsOf(participantId: sigil.participant.ParticipantId, convId: Id[Conversation]): Task[List[String]] =
    TestSigil.projectionFor(participantId, convId).map(_.suggestedTools.map(_.value))

  "Sigil pagination navigator promotion (bug #202)" should {

    "auto-promote next_page + query_tool_output when a ToolResults carries a JsonPagedResult with hasMore = true" in {
      val convId = freshConvId()
      val page = JsonPagedResult(
        items       = Nil,
        hasMore     = true,
        page        = 0,
        pageSize    = 50,
        referenceId = "ref-1",
        callId      = Id[Event]("call-1")
      )
      for {
        _      <- setup(convId)
        _      <- publishPaginatedToolResult(convId, page)
        names  <- suggestedToolsOf(TestAgent, convId)
      } yield {
        names should contain("next_page")
        names should contain("query_tool_output")
      }
    }

    "auto-promote when there are no more pages but child nodeIds exist" in {
      val convId = freshConvId()
      val page = JsonPagedResult(
        items       = Nil,
        hasMore     = false,
        page        = 0,
        pageSize    = 50,
        referenceId = "ref-2",
        callId      = Id[Event]("call-2"),
        nodeIds     = List("node-a", "node-b")
      )
      for {
        _      <- setup(convId)
        _      <- publishPaginatedToolResult(convId, page)
        names  <- suggestedToolsOf(TestAgent, convId)
      } yield {
        names should contain("next_page")
        names should contain("query_tool_output")
      }
    }

    "NOT promote when a paginated result fits in one page with no children (nothing to navigate to)" in {
      val convId = freshConvId()
      val page = JsonPagedResult(
        items       = Nil,
        hasMore     = false,
        page        = 0,
        pageSize    = 50,
        referenceId = "ref-3",
        callId      = Id[Event]("call-3"),
        nodeIds     = Nil
      )
      for {
        _      <- setup(convId)
        _      <- publishPaginatedToolResult(convId, page)
        names  <- suggestedToolsOf(TestAgent, convId)
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
        toolName       = ToolName("non_paginated_probe"),
        participantId  = TestAgent,
        conversationId = convId,
        topicId        = TestTopicEntry.id,
        _id            = invokeId,
        state          = EventState.Complete
      )
      val result = ToolResults(
        schemas        = Nil,
        participantId  = TestAgent,
        conversationId = convId,
        topicId        = TestTopicEntry.id,
        outcome        = ToolOutcome.Success,
        typed          = Some(obj("result" -> str("ok"))),
        state          = EventState.Complete,
        role           = MessageRole.Tool,
        origin         = Some(invokeId)
      )
      for {
        _     <- setup(convId)
        _     <- TestSigil.publish(invoke)
        _     <- TestSigil.publish(result)
        names <- suggestedToolsOf(TestAgent, convId)
      } yield {
        names should not contain "next_page"
        names should not contain "query_tool_output"
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
