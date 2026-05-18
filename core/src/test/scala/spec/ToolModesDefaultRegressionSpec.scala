package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.{GlobalSpace, TurnContext}
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.provider.{ConversationMode, Mode}
import sigil.tool.{
  DiscoveryFilter, DiscoveryRequest, InMemoryToolFinder, Tool, ToolFinder,
  ToolInput, ToolName, TypedTool
}
import sigil.tool.discovery.CapabilityType
import sigil.tool.fs.{GlobTool, GrepTool, LocalFileSystemContext, ReadFileTool}

/**
 * Layered regression coverage for the Tool.modes default. Without
 * the universal-by-default contract, every tool that doesn't
 * explicitly enumerate the active mode disappears from
 * `find_capability` the moment the agent leaves ConversationMode —
 * the user-visible failure is "agent in CodingMode can't read a
 * file" because the filesystem family isn't tagged for coding.
 *
 * The four layers progressively pin down the contract:
 *
 *   1. The trait default itself is `Set.empty` — vanilla Tool /
 *      TypedTool authors get universal discoverability without
 *      knowing about modes at all.
 *   2. `DiscoveryFilter.passesAffinity` honors empty as universal
 *      regardless of which Mode the request is in.
 *   3. `Sigil.findCapabilities` surfaces empty-modes tools across
 *      multiple Modes for the kinds of natural-language queries
 *      agents actually write ("read file", "search files", etc.).
 *
 * Layer 4 (end-to-end agent-loop integration) lives in
 * [[ToolModesDefaultLiveSpec]] which self-skips when no live model
 * is reachable.
 */
class ToolModesDefaultRegressionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private case class StubInput(text: String = "") extends ToolInput derives RW
  ToolInput.register(RW.static(StubInput()))

  /**
   * Vanilla TypedTool authoring — no `modes` override. The default
   * must be empty for universal discoverability to hold.
   */
  private object VanillaTool
    extends TypedTool[StubInput](
      name = ToolName("vanilla_default_tool"),
      description = "A test tool that doesn't override modes."
    ) {
    override def paginate: Boolean = false

    override protected def executeTyped(input: StubInput, context: TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
  }

  private val fs = LocalFileSystemContext(basePath = None)
  private val readFile: Tool = new ReadFileTool(fs)
  private val grep: Tool = new GrepTool(fs)
  private val glob: Tool = new GlobTool(fs)
  private val filesystemTools: List[Tool] = List(readFile, grep, glob)

  private val finder: ToolFinder = InMemoryToolFinder(filesystemTools :+ VanillaTool)

  private def request(keywords: String, mode: Mode): DiscoveryRequest =
    DiscoveryRequest(
      keywords = keywords,
      chain = List(TestUser, TestAgent),
      mode = mode,
      callerSpaces = Set(GlobalSpace),
      conversationId = Some(Conversation.id(s"tool-modes-${rapid.Unique()}"))
    )

  // --- Layer 1: the Tool default is genuinely universal --------------------

  "Layer 1: Tool.modes default" should {
    "be Set.empty so vanilla tools are universally discoverable" in rapid.Task {
      VanillaTool.modes shouldBe Set.empty[lightdb.id.Id[Mode]]
    }

    "be Set.empty for the framework filesystem family — they declare no mode preference" in rapid.Task {
      readFile.modes shouldBe Set.empty[lightdb.id.Id[Mode]]
      grep.modes shouldBe Set.empty[lightdb.id.Id[Mode]]
      glob.modes shouldBe Set.empty[lightdb.id.Id[Mode]]
    }
  }

  // --- Layer 2: affinity filter respects empty-as-universal ----------------

  "Layer 2: DiscoveryFilter.passesAffinity" should {
    "pass empty-modes tools in every registered Mode" in rapid.Task {
      // Cover ConversationMode (the framework default) plus a non-
      // conversation mode (TestCodingMode). The bug the universal
      // default fixes is the second case.
      val modes: List[Mode] = List(ConversationMode, TestCodingMode, TestSkilledMode)
      modes.foreach { mode =>
        withClue(s"vanilla in $mode: ") {
          DiscoveryFilter.passesAffinity(VanillaTool, request("anything", mode)) shouldBe true
        }
        filesystemTools.foreach { t =>
          withClue(s"${t.name.value} in $mode: ") {
            DiscoveryFilter.passesAffinity(t, request("read", mode)) shouldBe true
          }
        }
      }
      succeed
    }
  }

  // --- Layer 3: find_capability surfaces filesystem tools across modes ----

  "Layer 3: Sigil.findCapabilities" should {
    "surface read_file in TestCodingMode for a natural-language query" in {
      TestSigil.setToolFinder(finder)
      TestSigil.findCapabilities(request("read file source code", TestCodingMode))
        .map { matches =>
          val toolMatches = matches.filter(_.capabilityType == CapabilityType.Tool)
          val names = toolMatches.map(_.name)
          withClue(s"matches=${matches.map(m => s"${m.name}(${m.capabilityType})").mkString(", ")}: ") {
            names should contain("read_file")
          }
        }
    }

    "surface grep in TestCodingMode for a search-shaped query" in {
      TestSigil.setToolFinder(finder)
      TestSigil.findCapabilities(request("search files for text pattern", TestCodingMode))
        .map { matches =>
          val toolMatches = matches.filter(_.capabilityType == CapabilityType.Tool)
          toolMatches.map(_.name) should contain("grep")
        }
    }

    "surface filesystem tools under TestSkilledMode (a non-Conversation, non-Coding mode)" in {
      TestSigil.setToolFinder(finder)
      TestSigil.findCapabilities(request("list files in directory", TestSkilledMode))
        .map { matches =>
          val toolMatches = matches.filter(_.capabilityType == CapabilityType.Tool)
          // glob is the strongest match; read_file may also surface.
          // The contract: the empty-modes tools aren't filtered out.
          val names = toolMatches.map(_.name).toSet
          val anyFs = names("glob") || names("read_file") || names("grep")
          withClue(s"got ${toolMatches.map(_.name)}: ")(anyFs shouldBe true)
        }
    }

    "still surface filesystem tools in ConversationMode (no regression)" in {
      TestSigil.setToolFinder(finder)
      TestSigil.findCapabilities(request("read file contents", ConversationMode))
        .map { matches =>
          val toolMatches = matches.filter(_.capabilityType == CapabilityType.Tool)
          toolMatches.map(_.name) should contain("read_file")
        }
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.clearToolFinder()
      TestSigil.shutdown.map(_ => succeed)
    }
  }
}
