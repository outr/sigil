package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.provider.ConversationMode
import sigil.tool.{DiscoveryRequest, InMemoryToolFinder}
import sigil.tool.discovery.CapabilityType

/**
 * Memory-related tools (`save_memory`, `unpin_memory`,
 * `list_memories`, …) declare `requiresAccessibleSpaces = true`
 * because they need a place to write to / read from. The framework's
 * roster computation (`runAgentTurn`) and discovery path
 * (`findCapabilities`) filter them out for chains where
 * `accessibleSpaces` returns empty — surfacing them would just waste
 * tokens on tools the agent would fail to use.
 *
 * TestSigil's default static roster ships no `requiresAccessibleSpaces`
 * memory tool, so this spec installs a catalog containing `save_memory`
 * and drives `findCapabilities` directly: the same tool must be hidden
 * for an empty-`callerSpaces` request and surfaced once a space is
 * accessible — the only variable between the two cases is the gate.
 */
class MemoryToolGatingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setToolFinder(InMemoryToolFinder(List(sigil.tool.util.SaveMemoryTool(GlobalSpace))))

  private def request(callerSpaces: Set[sigil.SpaceId]): DiscoveryRequest =
    DiscoveryRequest(
      keywords = "save remember memory pinned list",
      chain = List(TestUser, TestAgent),
      mode = ConversationMode,
      callerSpaces = callerSpaces
    )

  private def discoveredToolNames(callerSpaces: Set[sigil.SpaceId]): Task[List[String]] =
    TestSigil.findCapabilities(request(callerSpaces)).map { matches =>
      matches.collect { case m if m.capabilityType == CapabilityType.Tool => m.name }
    }

  "findCapabilities" should {
    "hide save_memory when callerSpaces is empty" in {
      TestSigil.reset()
      discoveredToolNames(Set.empty).map(_ should not contain "save_memory")
    }

    "surface save_memory when at least one space is accessible" in {
      TestSigil.reset()
      discoveredToolNames(Set(GlobalSpace)).map(_ should contain("save_memory"))
    }
  }

  "Tool.requiresAccessibleSpaces" should {
    "be true for the framework memory tools" in Task {
      sigil.tool.util.SaveMemoryTool(GlobalSpace).requiresAccessibleSpaces shouldBe true
      sigil.tool.context.ListMemoriesTool.requiresAccessibleSpaces shouldBe true
      sigil.tool.context.UnpinMemoryTool.requiresAccessibleSpaces shouldBe true
    }

    "be false for non-memory framework tools" in Task {
      sigil.tool.core.RespondTool.requiresAccessibleSpaces shouldBe false
      sigil.tool.core.NoResponseTool.requiresAccessibleSpaces shouldBe false
      sigil.tool.core.CancelTool.requiresAccessibleSpaces shouldBe false
      sigil.tool.core.FindCapabilityTool.requiresAccessibleSpaces shouldBe false
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.clearToolFinder()
      TestSigil.shutdown.map(_ => succeed)
    }
  }
}
