package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.tool.{DiscoveryRequest, InMemoryToolFinder, ToolFinder, ToolInput, ToolName, TypedTool}
import sigil.TurnContext

/**
 * Coverage for sigil bug #90 — `preferIfNoBetter` regression where
 * generic primitives + tools generally were drowned by mode matches in
 * `find_capability` results. The fix scores tools on the same absolute
 * scale as modes (via [[sigil.tool.DiscoveryFilter.score]]) so:
 *
 *   1. A directly-matching tool ranks above a mode that merely
 *      mentions one of the query's keywords in its description.
 *   2. A `preferIfNoBetter` tool still appears in the result list
 *      (no score-threshold filter buries it) — its position falls
 *      below domain-specific tools but it stays findable.
 *   3. With NO competing domain tool present, a `preferIfNoBetter`
 *      tool surfaces normally for queries that match it.
 */
class PreferIfNoBetterRankingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  case class GenericInput(payload: String) extends ToolInput derives RW

  case object GrepLikeTool extends TypedTool[GenericInput](
    name        = ToolName("grep_like"),
    description = "Search files by regex.",
    keywords    = Set("grep", "search", "regex", "find", "match", "lines")
  ) {
    override def preferIfNoBetter: Boolean = true
    override protected def executeTyped(input: GenericInput, ctx: TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
  }

  case object ReadFileLikeTool extends TypedTool[GenericInput](
    name        = ToolName("read_file_like"),
    description = "Read a file's contents.",
    keywords    = Set("read", "file", "open", "load")
  ) {
    override def preferIfNoBetter: Boolean = true
    override protected def executeTyped(input: GenericInput, ctx: TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
  }

  ToolInput.register(RW.static(GenericInput("")))

  private val finder: ToolFinder = InMemoryToolFinder(List(GrepLikeTool, ReadFileLikeTool))

  TestSigil.setToolFinder(finder)

  private def request(keywords: String): DiscoveryRequest =
    DiscoveryRequest(
      keywords       = keywords,
      chain          = List(TestUser, TestAgent),
      mode           = sigil.provider.ConversationMode,
      callerSpaces   = Set(GlobalSpace),
      conversationId = Some(Conversation.id(s"prefer-rank-${rapid.Unique()}"))
    )

  "Tool ranking (#90)" should {

    "surface a `preferIfNoBetter` tool that matches the query" in {
      // Query that names grep directly. The grep tool MUST appear
      // even though it carries the preferIfNoBetter penalty.
      TestSigil.findCapabilities(request("grep search regex find lines")).map { matches =>
        val toolNames = matches
          .filter(_.capabilityType.toString.toLowerCase.contains("tool"))
          .map(_.name)
        toolNames should contain ("grep_like")
      }
    }

    "rank a directly-matching tool above modes whose keywords don't clearly match" in {
      // The query keywords match the tool's curated set strongly; the
      // registered Modes (TestCodingMode, TestSkilledMode,
      // WebResearchMode) do not have these as curated keywords. The
      // top tool match should outrank every Mode match.
      TestSigil.findCapabilities(request("grep regex match lines")).map { matches =>
        val topTool = matches.find(_.capabilityType.toString.toLowerCase.contains("tool"))
        val topMode = matches.find(_.capabilityType.toString.toLowerCase.contains("mode"))
        topTool.map(_.name) shouldBe Some("grep_like")
        topMode match {
          case Some(m) => topTool.get.score should be >= m.score
          case None    => succeed
        }
      }
    }

    "preserve preferIfNoBetter ordering between two tools that both match" in {
      // Query that touches both tools' keyword sets so both surface,
      // but matches grep more strongly than read_file_like. The
      // uniform penalty applied to both shouldn't perturb the
      // relative order — grep stays first.
      TestSigil.findCapabilities(request("grep search read file")).map { matches =>
        val tools = matches
          .filter(_.capabilityType.toString.toLowerCase.contains("tool"))
          .map(_.name)
        tools should contain ("grep_like")
        tools should contain ("read_file_like")
        tools.indexOf("grep_like") should be < tools.indexOf("read_file_like")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
