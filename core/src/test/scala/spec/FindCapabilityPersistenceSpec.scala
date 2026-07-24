package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, DiscoveredCapability, ParticipantProjection, TopicEntry, TurnInput}
import sigil.event.{CapabilityResults, Event, ToolInvoke}
import sigil.signal.EventState
import sigil.tool.ToolContext
import sigil.tool.ToolName
import sigil.tool.core.{FindCapabilityInput, FindCapabilityTool}
import sigil.tool.discovery.{CapabilityMatch, CapabilityStatus, CapabilityType}

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
      sigil = TestSigil,
      chain = List(TestAgent),
      conversation = Conversation(_id = convId, topics = List(TestTopicEntry)),
      turnInput = TurnInput(conversationId = convId),
      model = TestSigil.defaultTestModel,
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
        override def toolNames: List[ToolName] = Nil // baseline empty
        override def tools: sigil.provider.ToolPolicy = sigil.provider.ToolPolicy.Standard
      }
      val roster = TestSigil.effectiveToolNames(
        agent = agent,
        mode = sigil.provider.ConversationMode,
        suggested = discovered,
        overlays = Nil,
        recentlyUsedTools = Set.empty
      )
      // Both discovered names present in the merged roster.
      roster should contain(ToolName("dispatch_workers"))
      roster should contain(ToolName("grep"))
    }
  }

  // ---- sigil #301 — across-turn persistence via ParticipantProjection ----

  /**
   * #301 supersedes #300's per-turn lifetime by routing find_capability
   * matches into [[ParticipantProjection.suggestedTools]]. Discoveries
   * survive turn boundaries; the next find_capability call REPLACES
   * them; conversation-boundary isolation is preserved.
   *
   * The per-loop TurnContext cache above remains — it drives the
   * "Capabilities you've already discovered (this turn)" prompt section
   * within one loop. The projection-overlay surface tested below is
   * what carries discoveries across turns.
   */

  private def conv(id: String): Conversation = Conversation(
    topics = List(TopicEntry(TestTopicId, "test", "test")),
    _id = Conversation.id(id)
  )

  private def turnContextFor(c: Conversation): TurnContext = TurnContext(
    sigil = TestSigil,
    chain = List(TestUser, TestAgent),
    conversation = c,
    turnInput = TurnInput(ConversationView(conversationId = c._id)),
    model = TestSigil.defaultTestModel
  )

  private def capabilityResults(convId: Id[Conversation],
                                names: List[String],
                                query: String): CapabilityResults =
    CapabilityResults(
      matches = names.map(n =>
        CapabilityMatch(
          name = n,
          description = s"stub: $n",
          capabilityType = CapabilityType.Tool,
          score = 1.0,
          status = CapabilityStatus.Ready
        )),
      participantId = TestAgent,
      conversationId = convId,
      topicId = TestTopicId,
      query = query,
      state = EventState.Complete,
      // Tool-role events must point at a parent ToolInvoke. In real
      // dispatch the framework stamps this from `ctx.invokeId`; the
      // tests stamp a synthetic id so validateEventInvariants accepts
      // the synthetic event for projection-handler exercise.
      origin = Some(Event.id())
    )

  "FindCapabilityTool (sigil #301)" should {

    "emit a CapabilityResults event so the projection handler can route it" in {
      val c = conv(s"fc-emit-${rapid.Unique()}")
      val tc = ToolContext(turnContextFor(c), Event.id(), FindCapabilityTool.name)
      FindCapabilityTool.executeResult(
        FindCapabilityInput(keywords = "slack message channel"),
        tc
      ).map { _ =>
        val capabilityEvents = tc.emittedEvents.collect { case cr: CapabilityResults => cr }
        capabilityEvents should have size 1
        val emitted = capabilityEvents.head
        emitted.participantId shouldBe TestAgent
        emitted.conversationId shouldBe c._id
        emitted.query shouldBe "slack message channel"
        emitted.matches should not be empty
      }
    }
  }

  "ParticipantProjection.suggestedTools (sigil #301)" should {

    "carry find_capability matches after a CapabilityResults event is published — survives turn boundary" in {
      val c = conv(s"fc-persist-${rapid.Unique()}")
      val convId = c._id
      val cr = capabilityResults(convId, List("dispatch_workers", "grep"), query = "find grep files")
      for {
        _ <- TestSigil.publish(cr)
        proj <- TestSigil.projectionFor(TestAgent, convId)
      } yield
        // With the per-turn clearSuggestedTools call removed, the
        // projection retains the discoveries across turn boundaries —
        // the next find_capability is the only thing that replaces.
        proj.suggestedTools.map(_.value) should contain allOf ("dispatch_workers", "grep")
    }

    "ACCUMULATE matches across find_capability calls — a prior discovery isn't evicted (sigil #383)" in {
      val c = conv(s"fc-accumulate-${rapid.Unique()}")
      val convId = c._id
      val first = capabilityResults(convId, List("dispatch_workers", "grep"), query = "find grep files")
      val second = capabilityResults(convId, List("bsp_test", "bsp_compile"), query = "test compile build")
      for {
        _ <- TestSigil.publish(first)
        afterFirst <- TestSigil.projectionFor(TestAgent, convId)
        _ <- TestSigil.publish(second)
        afterSecond <- TestSigil.projectionFor(TestAgent, convId)
      } yield {
        afterFirst.suggestedTools.map(_.value) should contain allOf ("dispatch_workers", "grep")
        // Union, not replace (#383): a tool the agent discovered (and may be
        // about to dispatch) must stay in the roster even after a later search
        // returns a different set.
        afterSecond.suggestedTools.map(_.value) should contain allOf ("dispatch_workers", "grep", "bsp_test", "bsp_compile")
      }
    }

    "NOT evict a recently-USED discovery when a new find_capability fires (sigil #377)" in {
      val c = conv(s"fc-inuse-${rapid.Unique()}")
      val convId = c._id
      val first = capabilityResults(convId, List("update_workflow", "run_workflow"), query = "create workflow")
      // The agent USES update_workflow (lands in recentToolInvocations).
      val invoke = ToolInvoke(
        toolName = ToolName("update_workflow"),
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicId,
        state = EventState.Complete,
        origin = Some(Event.id())
      )
      val second = capabilityResults(convId, List("grep", "read_file"), query = "search files")
      for {
        _ <- TestSigil.publish(first)
        _ <- TestSigil.publish(invoke)
        _ <- TestSigil.publish(second)
        proj <- TestSigil.projectionFor(TestAgent, convId)
      } yield {
        val names = proj.suggestedTools.map(_.value)
        // Every discovery is retained (#383 — additive), incl. the in-use tool
        // and the new search's matches; nothing the agent found is evicted.
        names should contain allOf ("update_workflow", "run_workflow", "grep", "read_file")
      }
    }

    "scope discoveries per-conversation — a new conversation's projection starts empty" in {
      val cA = conv(s"fc-isolate-A-${rapid.Unique()}")
      val cB = conv(s"fc-isolate-B-${rapid.Unique()}")
      val cr = capabilityResults(cA._id, List("dispatch_workers", "grep"), query = "find grep files")
      for {
        _ <- TestSigil.publish(cr)
        inA <- TestSigil.projectionFor(TestAgent, cA._id)
        inB <- TestSigil.projectionFor(TestAgent, cB._id)
      } yield {
        inA.suggestedTools.map(_.value) should contain allOf ("dispatch_workers", "grep")
        // Same participant, different conversation — preserves #226's
        // "no cross-conversation pollution" invariant while restoring
        // within-conversation persistence.
        inB.suggestedTools shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
