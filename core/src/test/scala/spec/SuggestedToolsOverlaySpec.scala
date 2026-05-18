package spec

import fabric.define.{DefType, Definition}
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.Conversation
import sigil.event.{CapabilityResults, Event, ToolResults}
import sigil.signal.EventState
import sigil.tool.{ToolName, ToolSchema}
import sigil.tool.discovery.{CapabilityMatch, CapabilityStatus, CapabilityType}

/**
 * Regression for sigil bug #169 — the `suggestedTools` overlay was
 * decayed at every iteration boundary, which broke any flow that
 * required a prerequisite call between the `find_capability` that
 * discovered the tool and the iteration that invoked it. Worst case:
 * `find_capability → record_consent → invoke gated tool` lost the
 * gated tool to decay between steps 2 and 3.
 *
 * New semantics:
 *   - Overlay persists across iterations within a single user turn.
 *   - `CapabilityResults` replaces the overlay.
 *   - `ToolResults(schemas = nonEmpty)` replaces the overlay so tools
 *     can suggest natural-progression follow-ups
 *     (`create_workflow` → `[add_workflow_step, add_trigger]`).
 *   - `ToolResults(schemas = Nil)` leaves the overlay UNCHANGED —
 *     tools that don't participate in progression flows no longer
 *     wipe a prior discovery.
 *   - Overlay is cleared in the loop's `terminate()` path; this spec
 *     covers the projection-handler rules, not the loop-end clear
 *     (which is exercised by integration paths).
 */
class SuggestedToolsOverlaySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("sst-overlay")
  private val topicId = TestTopicEntry.id

  /**
   * Tool-role events require `origin` per Sigil.validateEventInvariants
   * — point each to a fixed synthetic id so the validator passes
   * without having to publish a real parent ToolInvoke.
   */
  private val syntheticOrigin: Id[Event] = Id[Event]("sst-overlay-parent-invoke")

  // Idempotent — repeated initFor + setup blocks across tests share the same DB
  // path. Each test seeds a fresh conv via withDB.upsert so projection state is
  // independent.

  private def seedConversation(): rapid.Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
      _id = convId,
      topics = TestTopicStack
    )))).map(_ => ())

  private def projection: rapid.Task[List[ToolName]] =
    TestSigil.withDB(_.participantProjections.transaction { tx =>
      tx.list.map(_.filter(_.conversationId == convId).flatMap(_.suggestedTools).distinct)
    })

  private def fakeSchema(name: String): ToolSchema = ToolSchema(
    id = Id[ToolSchema](name),
    name = ToolName(name),
    description = s"fake $name",
    input = Definition(defType = DefType.Obj(Map.empty)),
    examples = Nil
  )

  private def capability(name: String): CapabilityMatch = CapabilityMatch(
    name = name,
    description = s"fake $name",
    capabilityType = CapabilityType.Tool,
    score = 1.0,
    status = CapabilityStatus.Ready
  )

  "CapabilityResults" should {
    "populate suggestedTools with the matched tool names" in {
      for {
        _ <- seedConversation()
        // Clear any leftover projection state from prior tests
        _ <- TestSigil.withDB(_.participantProjections.transaction { tx =>
          tx.list.flatMap(rows => rapid.Task.sequence(rows.map(r => tx.delete(r._id))).unit)
        })
        _ <- TestSigil.publish(CapabilityResults(
          matches = List(capability("load_claude_state")),
          participantId = TestUser,
          conversationId = convId,
          topicId = topicId,
          query = "load claude state",
          state = EventState.Complete,
          origin = Some(syntheticOrigin)
        ))
        names <- projection
      } yield names should contain(ToolName("load_claude_state"))
    }
  }

  "ToolResults with non-empty schemas" should {
    "replace suggestedTools (natural-progression flow)" in {
      for {
        _ <- seedConversation()
        _ <- TestSigil.withDB(_.participantProjections.transaction { tx =>
          tx.list.flatMap(rows => rapid.Task.sequence(rows.map(r => tx.delete(r._id))).unit)
        })
        // Discovery step: agent calls find_capability and gets create_workflow.
        _ <- TestSigil.publish(CapabilityResults(
          matches = List(capability("create_workflow")),
          participantId = TestUser,
          conversationId = convId,
          topicId = topicId,
          query = "workflow",
          state = EventState.Complete,
          origin = Some(syntheticOrigin)
        ))
        afterDiscovery <- projection
        // Build step: create_workflow emits ToolResults with progression suggestions.
        _ <- TestSigil.publish(ToolResults(
          schemas = List(fakeSchema("add_workflow_step"), fakeSchema("add_trigger")),
          participantId = TestUser,
          conversationId = convId,
          topicId = topicId,
          state = EventState.Complete,
          origin = Some(syntheticOrigin)
        ))
        afterBuild <- projection
      } yield {
        afterDiscovery should contain(ToolName("create_workflow"))
        afterBuild should contain allOf (ToolName("add_workflow_step"), ToolName("add_trigger"))
        afterBuild shouldNot contain(ToolName("create_workflow")) // replaced, not merged
      }
    }
  }

  "ToolResults with empty schemas" should {
    "leave suggestedTools UNCHANGED (bug #169 — was wiping the overlay)" in {
      for {
        _ <- seedConversation()
        _ <- TestSigil.withDB(_.participantProjections.transaction { tx =>
          tx.list.flatMap(rows => rapid.Task.sequence(rows.map(r => tx.delete(r._id))).unit)
        })
        _ <- TestSigil.publish(CapabilityResults(
          matches = List(capability("load_claude_state")),
          participantId = TestUser,
          conversationId = convId,
          topicId = topicId,
          query = "load claude state",
          state = EventState.Complete,
          origin = Some(syntheticOrigin)
        ))
        beforeNoOp <- projection
        // Simulate an unrelated tool result (e.g. record_consent's
        // confirmation Message, or a bash exec's result). ToolResults
        // with schemas = Nil is the framework default for everything
        // except natural-progression tools.
        _ <- TestSigil.publish(ToolResults(
          schemas = Nil,
          participantId = TestUser,
          conversationId = convId,
          topicId = topicId,
          state = EventState.Complete,
          origin = Some(syntheticOrigin)
        ))
        afterNoOp <- projection
      } yield {
        beforeNoOp should contain(ToolName("load_claude_state"))
        afterNoOp shouldBe beforeNoOp // unchanged — the bug-fix invariant
      }
    }
  }

  "Multi-step workflow" should {
    "preserve add_workflow_step across repeated invocations when each call re-emits suggestions" in {
      for {
        _ <- seedConversation()
        _ <- TestSigil.withDB(_.participantProjections.transaction { tx =>
          tx.list.flatMap(rows => rapid.Task.sequence(rows.map(r => tx.delete(r._id))).unit)
        })
        // create_workflow seeds the progression suggestions.
        _ <- TestSigil.publish(ToolResults(
          schemas = List(fakeSchema("add_workflow_step"), fakeSchema("add_trigger")),
          participantId = TestUser,
          conversationId = convId,
          topicId = topicId,
          state = EventState.Complete,
          origin = Some(syntheticOrigin)
        ))
        // 5x add_workflow_step calls, each re-emitting the same suggestions
        // (the convention for natural-progression tools).
        _ <- rapid.Task.sequence((1 to 5).map { _ =>
          TestSigil.publish(ToolResults(
            schemas = List(fakeSchema("add_workflow_step"), fakeSchema("add_trigger")),
            participantId = TestUser,
            conversationId = convId,
            topicId = topicId,
            state = EventState.Complete,
            origin = Some(syntheticOrigin)
          ))
        }.toList)
        afterSteps <- projection
      } yield afterSteps should contain allOf (ToolName("add_workflow_step"), ToolName("add_trigger"))
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
