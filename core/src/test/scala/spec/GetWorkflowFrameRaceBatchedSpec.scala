package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextFrame, Conversation, ToolCallState, Topic}
import sigil.event.{Event, ToolInvoke, ToolOutcome}
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.ToolName
import sigil.workflow.tool.{GetWorkflowInput, GetWorkflowOutput}

/**
 * Reproduces the frame-race ("result did not reach this turn / raced past the
 * prompt") under the agent loop's batched-events transaction.
 *
 * The isolated settle (each delta in its own transaction) renders correctly —
 * but the live loop publishes an iteration's events inside a single
 * `withBatchedEvents` scope. If `attachContextFrameOnSettle` re-reads the
 * invoke and the join transaction doesn't reflect the just-applied result
 * delta's fold, the frame is computed from the still-Pending invoke and the
 * `#354` placeholder is inlined — exactly the poison seen on `find_capability`
 * and `get_workflow` in the Sage wire log.
 *
 * This publishes the invoke + the two settling deltas INSIDE one batched scope
 * (as the loop does) and asserts the committed frame carries the result.
 */
class GetWorkflowFrameRaceBatchedSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  "A tool settled inside the agent loop's batched-events scope" should {
    "inline its result into the ContextFrame, not the #354 'raced past' placeholder" in {
      val convId = Conversation.id(s"gw-batch-${rapid.Unique()}")
      val topicId = Topic.id(s"t-${rapid.Unique()}")
      val invokeId = Event.id()
      val invoke = ToolInvoke(
        toolName = ToolName("get_workflow"),
        participantId = WorkflowTestUser,
        conversationId = convId,
        topicId = topicId,
        input = Some(GetWorkflowInput("wf-race")),
        state = EventState.Active,
        _id = invokeId
      )
      val foundOutput = GetWorkflowOutput.Found(
        workflowId = "wf-race",
        name = "race-target",
        enabled = true,
        description = None,
        space = "global",
        steps = Nil,
        triggers = Nil,
        variables = Nil,
        tags = Nil
      )
      val inputDelta = ToolDelta(
        target = invokeId,
        conversationId = convId,
        input = Some(GetWorkflowInput("wf-race")),
        state = Some(EventState.Complete)
      )
      val resultDelta = ToolDelta(
        target = invokeId,
        conversationId = convId,
        output = Some(foundOutput),
        outcome = Some(ToolOutcome.Success),
        state = Some(EventState.Complete)
      )

      // Publish all three INSIDE one batched-events scope, the way
      // runAgentLoop wraps an iteration's publishes.
      TestWorkflowSigil.withDB { db =>
        db.withBatchedEvents(convId) {
          TestWorkflowSigil.publish(invoke)
            .flatMap(_ => TestWorkflowSigil.publish(inputDelta))
            .flatMap(_ => TestWorkflowSigil.publish(resultDelta))
        }
      }.flatMap { _ =>
        TestWorkflowSigil.withDB(_.events.transaction(_.get(invokeId)))
      }.map { ev =>
        val rendered = ev.flatMap(_.contextFrame) match {
          case Some(tc: ContextFrame.ToolCall) =>
            tc.state match {
              case ToolCallState.Complete(content, _) => content
              case ToolCallState.Active => "(active)"
            }
          case other => s"(no ToolCall frame: $other)"
        }
        withClue(s"inlined frame content: $rendered\n") {
          rendered should include("race-target")
          rendered should not include "did not reach this turn"
        }
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
