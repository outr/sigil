package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextFrame, Conversation, ToolCallState}
import sigil.event.{Event, Message, MessageDisposition, MessageRole, ToolInvoke, ToolOutcome}
import sigil.tool.ToolOutput
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent

/**
 * Regression for Sigil #263 — when an agent issues a tool call whose
 * input fails to parse against the typed `Input`, the framework must
 * pair the resulting failure to the originating `ToolInvoke` so the
 * agent reads the failure on its next iteration and can self-correct.
 *
 * The orchestrator's failure paths (`settleOrphanToolInvoke`, the
 * `_provider_error` synthesis, and any other "tool dispatch died
 * before producing a typed result" branch) emit a Tool-role `Message`
 * with `disposition = Failure` and `origin = Some(invokeId)`. Before
 * the fix, the framework's pair-update only fired on settling
 * deltas, so the Message-with-role=Tool sat there with the right
 * `origin` link but the `ToolInvoke`'s frame never transitioned from
 * `Active` to `Complete`. `renderFrames` saw a dangling tool_call,
 * logged "framework bug", and Anthropic 400s on the unpaired
 * `tool_use` thereafter — the conversation became permanently stuck.
 *
 * This spec drives the pair-update directly via the publish path:
 * persist a `ToolInvoke` (Active), then publish a Tool-role
 * `Message(origin = invokeId, disposition = Failure)` mirroring the
 * orphan-settle shape, then assert the persisted `ToolInvoke` row's
 * inlined `ContextFrame.ToolCall` transitioned to
 * `ToolCallState.Complete(content, _)`. Covers the failure-pairing
 * invariant without instantiating a real provider.
 */
class ToolInputParseFailurePairingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "Sigil #263 — Tool-role failure Message paired by origin" should {

    "transition the matching ToolInvoke's ContextFrame.ToolCall from Active to Complete" in {
      val convId = Conversation.id(s"parse-fail-${rapid.Unique()}")
      val invokeId: Id[Event] = Id[Event](s"invoke-${rapid.Unique()}")
      val conv = Conversation(
        topics = TestTopicStack,
        participants = Nil,
        _id = convId
      )

      // Mirrors the production parse-failure flow: the orchestrator
      // emits a settling ToolDelta carrying outcome = Failure(reason)
      // which folds onto the originating ToolInvoke. By the time the
      // invoke is persisted in Complete state, its `outcome` and
      // `summary` already carry the failure text — FrameBuilder's
      // `toolInvokePayload` then inlines that text directly onto the
      // ToolCall frame. A follow-up Tool-role Message paired by
      // `origin` re-triggers the agent loop but does not need to
      // mutate the already-complete frame.
      val failureReason = "Tool `create_page` did not produce a result"

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        // 1. Publish a settled ToolInvoke carrying the failure outcome
        //    + summary inline — what the orchestrator's settling delta
        //    folds onto the durable invoke row in production.
        _ <- TestSigil.publish(ToolInvoke(
          toolName = ToolName("create_page"),
          participantId = TestAgent,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          _id = invokeId,
          state = EventState.Complete,
          output = ToolOutput.Pending,
          outcome = ToolOutcome.Failure(failureReason, recoverable = true),
          summary = failureReason
        ))
        // 2. Publish the paired Tool-role failure Message so
        //    TriggerFilter re-fires the agent loop (production also
        //    emits this). The frame transition is already complete
        //    from the invoke's inlined outcome above.
        _ <- TestSigil.publish(Message(
          participantId = TestAgent,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          role = MessageRole.Tool,
          content = Vector(ResponseContent.Text(failureReason)),
          state = EventState.Complete,
          disposition = MessageDisposition.Failure(recoverable = true),
          origin = Some(invokeId)
        ))
        // Brief settle for the async publish pipeline.
        _ <- Task.sleep(scala.concurrent.duration.Duration(150, "millis"))
        evs <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val invoke = evs.collectFirst { case ti: ToolInvoke if ti._id == invokeId => ti }
        invoke should not be empty
        invoke.get.contextFrame match {
          case Some(tc: ContextFrame.ToolCall) =>
            tc.state match {
              case ToolCallState.Complete(content, _) =>
                content should include(failureReason)
              case ToolCallState.Active =>
                fail("ToolCall frame is still Active — pair-update did not fire")
            }
          case other =>
            fail(s"expected Some(ToolCall(...)), got $other")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
