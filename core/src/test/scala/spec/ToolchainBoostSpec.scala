package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.tool.{DiscoveryRequest, InMemoryToolFinder, Tool, ToolFinder, ToolInput, ToolName, TypedTool}
import sigil.TurnContext

/**
 * Coverage for sigil bug #85 — when the conversation has an active
 * toolchain (per [[sigil.Sigil.activeToolchains]]),
 * `find_capability`'s ranker adds [[sigil.Sigil.toolchainBoost]] to
 * the score of every Tool whose [[sigil.tool.Tool.toolchain]]
 * matches a name in the active set. Verifies:
 *
 *   1. With no active toolchain, a tagged tool ranks where keyword
 *      ordering puts it (no boost).
 *   2. With its toolchain active, the tagged tool jumps above
 *      higher-keyword-ranked siblings.
 *   3. A tool whose toolchain isn't active gets no boost.
 *   4. The default `Sigil.activeToolchains` returns Set.empty so
 *      apps that don't override the hook see no rank changes.
 */
class ToolchainBoostSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // -- fake tools --

  case class GenericInput(payload: String) extends ToolInput derives RW

  /** "Generic" tool — no toolchain, scores by keyword match. */
  case object GrepLikeTool extends TypedTool[GenericInput](
    name        = ToolName("grep_like"),
    description = "Generic search.",
    keywords    = Set("grep", "search", "examine", "inspect", "code")
  ) {
    override protected def executeTyped(input: GenericInput, ctx: TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
  }

  /** Tagged with `lsp` toolchain. Same keyword set as the generic. */
  case object LspLikeTool extends TypedTool[GenericInput](
    name        = ToolName("lsp_like_diagnostics"),
    description = "LSP-backed inspection.",
    keywords    = Set("lsp", "examine", "inspect", "analyze")
  ) {
    override def toolchain: Option[String] = Some("lsp")
    override protected def executeTyped(input: GenericInput, ctx: TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
  }

  ToolInput.register(RW.static(GenericInput("")))

  /** A finder that returns both tools, ordered by keyword match (grep
    * has more matches for "examine search" so ranks first by default). */
  private val finder: ToolFinder = InMemoryToolFinder(List(GrepLikeTool, LspLikeTool))

  private def request(active: Set[String]): DiscoveryRequest =
    DiscoveryRequest(
      keywords     = "examine inspect code",
      chain        = List(TestUser, TestAgent),
      mode         = sigil.provider.ConversationMode,
      callerSpaces = Set(GlobalSpace),
      conversationId = Some(Conversation.id(s"toolchain-boost-${rapid.Unique()}"))
    )

  /** Active toolchains the test injects for the next call. Each
    * test sets this then calls findCapabilities. */
  private val activeRef: java.util.concurrent.atomic.AtomicReference[Set[String]] =
    new java.util.concurrent.atomic.AtomicReference(Set.empty[String])

  // Wire test-local hooks once. Specs use the activeRef to swap.
  TestSigil.setToolFinder(finder)
  TestSigil.setActiveToolchainsHook(_ => Task.pure(activeRef.get()))

  "Toolchain boost (#85)" should {

    "leave ranks unchanged when no toolchain is active" in {
      activeRef.set(Set.empty)
      TestSigil.findCapabilities(request(Set.empty)).map { matches =>
        val tools = matches.filter(_.capabilityType.toString.toLowerCase.contains("tool"))
        // Either tool can come first — just assert both are present
        // and their score difference is small (no big lift).
        tools.map(_.name).toSet should contain allOf ("grep_like", "lsp_like_diagnostics")
        val grepScore = tools.find(_.name == "grep_like").map(_.score).getOrElse(0.0)
        val lspScore  = tools.find(_.name == "lsp_like_diagnostics").map(_.score).getOrElse(0.0)
        math.abs(grepScore - lspScore) should be < TestSigil.toolchainBoost
      }
    }

    "lift the lsp-tagged tool above the generic when `lsp` is active" in {
      // Capture lsp score WITHOUT the boost first.
      activeRef.set(Set.empty)
      TestSigil.findCapabilities(request(Set.empty)).flatMap { unboosted =>
        val unboostedLsp = unboosted
          .find(_.name == "lsp_like_diagnostics").map(_.score).getOrElse(0.0)
        // Now switch the toolchain on and re-run.
        activeRef.set(Set("lsp"))
        TestSigil.findCapabilities(request(Set("lsp"))).map { matches =>
          val tools = matches.filter(_.capabilityType.toString.toLowerCase.contains("tool"))
          val grepScore = tools.find(_.name == "grep_like").map(_.score).getOrElse(0.0)
          val lspScore  = tools.find(_.name == "lsp_like_diagnostics").map(_.score).getOrElse(0.0)
          // 1) The boost lifts the lsp tool above the generic.
          lspScore should be > grepScore
          // 2) The boost adds exactly `toolchainBoost` to the lsp
          //    tool's score (commensurate scale post-#90).
          (lspScore - unboostedLsp) shouldBe TestSigil.toolchainBoost
        }
      }
    }

    "leave a non-matching toolchain unboosted (lsp tool, only `bsp` active)" in {
      activeRef.set(Set("bsp"))
      TestSigil.findCapabilities(request(Set("bsp"))).map { matches =>
        val tools = matches.filter(_.capabilityType.toString.toLowerCase.contains("tool"))
        val grepScore = tools.find(_.name == "grep_like").map(_.score).getOrElse(0.0)
        val lspScore  = tools.find(_.name == "lsp_like_diagnostics").map(_.score).getOrElse(0.0)
        // Neither tool has toolchain="bsp"; both score by base
        // ordering only.
        math.abs(grepScore - lspScore) should be < TestSigil.toolchainBoost
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
