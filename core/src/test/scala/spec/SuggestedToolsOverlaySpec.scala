package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.Conversation
import sigil.event.{CapabilityResults, Event}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.discovery.{CapabilityMatch, CapabilityStatus, CapabilityType}

/**
 * Regression for sigil bug #169 — the `suggestedTools` overlay was
 * decayed at every iteration boundary, which broke any flow that
 * required a prerequisite call between the `find_capability` that
 * discovered the tool and the iteration that invoked it. Worst case:
 * `find_capability → record_consent → invoke gated tool` lost the
 * gated tool to decay between steps 2 and 3.
 *
 * Sigil #265 collapsed the tool transaction onto a self-settling
 * `ToolInvoke`, removing the legacy `ToolResults.schemas`-driven
 * overlay update path entirely. `suggestedNextTools` is now a static
 * property declared on the `Tool` instance and merged (not replaced)
 * into the overlay when the invoke settles. This spec now covers
 * only the `CapabilityResults`-driven branch of the overlay rule
 * (the `find_capability` discovery cache); coverage for the
 * `suggestedNextTools` merge lives in `PaginationNavigatorPromotionSpec`
 * and `Bugs203To206RegressionSpec`.
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

  // Sigil #265 — the previous `ToolResults(schemas = …)`-driven
  // overlay tests are obsolete. The new model declares
  // `suggestedNextTools` statically on the Tool, and the projection
  // path merges them when the invoke settles. Coverage for that
  // behavior lives in `Bugs203To206RegressionSpec` and
  // `PaginationNavigatorPromotionSpec`; this spec is restricted to
  // the `CapabilityResults` branch above.

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
