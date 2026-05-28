package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, DiscoveredCapability, ParticipantProjection, TurnInput}
import sigil.tool.ToolName

import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for sigil bug #300 — `find_capability` matches must
 * survive across iterations of the same agent loop (the cache is
 * scoped to "this user turn," not "this iteration"). #226 moved the
 * cache from a persisted projection field to an `AtomicReference[Map]`
 * threaded through every iteration of one `runAgentLoop` call; the
 * bug doc claims the matches die at iteration boundaries despite
 * that threading.
 *
 * This spec verifies the underlying [[TurnContext]] / `discoveredCapabilitiesRef`
 * lifecycle: a shared ref carried across multiple TurnContext
 * instances (the per-iteration shape) preserves discoveries the same
 * way runAgentLoop threads its ref. If the ref-based lifecycle works
 * here, the bug's wire-side symptom is elsewhere (narrowing dropping
 * `state.extras`, projection.suggestedTools clearing, etc.).
 */
class FindCapabilityPersistenceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId: Id[Conversation] = Conversation.id(s"find-cap-${rapid.Unique()}")

  private def ctxWith(ref: AtomicReference[Map[String, DiscoveredCapability]]): TurnContext =
    TurnContext(
      sigil                     = TestSigil,
      chain                     = List(TestAgent),
      conversation              = Conversation(_id = convId, topics = List(TestTopicEntry)),
      turnInput                 = TurnInput(conversationId = convId),
      model                     = TestSigil.defaultTestModel,
      discoveredCapabilitiesRef = ref
    )

  "TurnContext.discoveredCapabilitiesRef lifecycle (sigil #300)" should {

    "preserve recordDiscovery results when a fresh TurnContext is built around the same ref" in Task {
      val ref = new AtomicReference[Map[String, DiscoveredCapability]](Map.empty)

      // Iteration 1: a TurnContext built around the ref records two
      // discoveries — `find_capability("foo")` matched `tool_a, tool_b`.
      val iter1 = ctxWith(ref)
      iter1.recordDiscovery("foo", List(ToolName("tool_a"), ToolName("tool_b")))
      iter1.discoveredCapabilities should have size 1
      iter1.discoveredCapabilities("foo").matches shouldBe List(ToolName("tool_a"), ToolName("tool_b"))

      // Iteration 2: framework creates a FRESH TurnContext for the
      // next iteration but threads the SAME ref (the per-loop shared
      // AtomicReference). The fresh ctx must see the discovery iter
      // 1 recorded.
      val iter2 = ctxWith(ref)
      iter2.discoveredCapabilities should have size 1
      iter2.discoveredCapabilities("foo").matches shouldBe List(ToolName("tool_a"), ToolName("tool_b"))

      // Iteration 2 records a second discovery; iteration 3 sees both.
      iter2.recordDiscovery("bar", List(ToolName("tool_c")))
      val iter3 = ctxWith(ref)
      iter3.discoveredCapabilities.keySet shouldBe Set("foo", "bar")
    }

    "drop everything on clearDiscoveredCapabilities (the turn-end signal)" in Task {
      val ref = new AtomicReference[Map[String, DiscoveredCapability]](Map.empty)
      val ctx = ctxWith(ref)
      ctx.recordDiscovery("foo", List(ToolName("tool_a")))
      ctx.discoveredCapabilities should have size 1
      ctx.clearDiscoveredCapabilities()
      ctx.discoveredCapabilities shouldBe empty
      // A fresh TurnContext around the now-empty ref starts clean
      // too — proves the clear is visible cross-instance via the
      // shared ref.
      val iter2 = ctxWith(ref)
      iter2.discoveredCapabilities shouldBe empty
    }

    "isolate two runs that started with their own refs (cross-turn isolation)" in Task {
      val turn1Ref = new AtomicReference[Map[String, DiscoveredCapability]](Map.empty)
      val turn2Ref = new AtomicReference[Map[String, DiscoveredCapability]](Map.empty)
      ctxWith(turn1Ref).recordDiscovery("foo", List(ToolName("tool_a")))
      // turn 2 starts with a fresh ref — must not see turn 1's
      // discovery. This is the cross-turn guarantee #226 protected.
      ctxWith(turn2Ref).discoveredCapabilities shouldBe empty
      // turn 1 still has it (independent ref).
      ctxWith(turn1Ref).discoveredCapabilities.keySet shouldBe Set("foo")
    }
  }

  "Sigil.effectiveToolNames (sigil #299 / #300 wire-vs-prompt seam)" should {

    "include discoveredCapabilities-derived tools in the merged roster" in Task {
      // Build a fake projection with no suggestedTools (simulates a
      // fresh agent loop) and a discoveredCapabilities ref carrying
      // one discovery. The merger should pick up the discovered
      // names via the `suggested` arg.
      val discovered = List(ToolName("dispatch_workers"), ToolName("grep"))
      val agent = new sigil.participant.AgentParticipant {
        override def id: sigil.participant.AgentParticipantId = TestAgent
        override def modelId: lightdb.id.Id[sigil.db.Model] =
          sigil.db.Model.id("test", "find-cap-merger")
        override def roles: List[sigil.role.Role] = List(sigil.role.GeneralistRole)
        override def displayName: String = "MergerAgent"
        override def avatarUrl: Option[String] = None
        override def toolNames: List[ToolName] = Nil  // baseline empty
        override def tools: sigil.provider.ToolPolicy = sigil.provider.ToolPolicy.Standard
      }
      val roster = TestSigil.effectiveToolNames(
        agent             = agent,
        mode              = sigil.provider.ConversationMode,
        suggested         = discovered,
        overlays          = Nil,
        recentlyUsedTools = Set.empty
      )
      // Both discovered names present in the merged roster.
      roster should contain (ToolName("dispatch_workers"))
      roster should contain (ToolName("grep"))
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
