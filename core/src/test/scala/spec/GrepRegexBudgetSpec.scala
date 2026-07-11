package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.ToolOutcome
import sigil.signal.ToolDelta
import sigil.tool.ToolContext
import sigil.tool.fs.{FileSystemContext, GrepTool, LocalFileSystemContext, RegexBudgetExceededException}
import sigil.tool.model.GrepInput

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * #318 — `grep` must bound a model-authored regex. A
 * catastrophic-backtracking pattern would otherwise wedge the tool (and
 * the agent loop awaiting it) for minutes. The per-line step budget on
 * [[LocalFileSystemContext]] aborts it promptly with an actionable
 * recoverable failure.
 */
class GrepRegexBudgetSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * A pattern + line that backtracks catastrophically under
   * `java.util.regex` on a current JDK. (JDK 21 optimizes the classic
   * nested-quantifier `(a+)+$` form, so that no longer blows up —
   * `.*` inside a bounded repetition over a whitespace-laden line that
   * fails the trailing literal still does, the same backtracking mode
   * as the field's many-alternation `bug\s+…` pattern.)
   */
  private val PathologicalPattern = "(.*\\s){15}x"
  private val PathologicalLine = "w " * 40

  private def withTempDir[T](budget: Long)(body: (FileSystemContext, Path) => Task[T]): Task[T] = Task.defer {
    val dir = Files.createTempDirectory(s"grep-budget-${rapid.Unique()}-")
    val ctx = new LocalFileSystemContext(Some(dir), regexStepBudget = budget)
    body(ctx, dir).guarantee(Task {
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    })
  }

  private def write(dir: Path, name: String, content: String): Unit = {
    val p = dir.resolve(name)
    Option(p.getParent).foreach(Files.createDirectories(_))
    Files.writeString(p, content)
    ()
  }

  private def turnContext(): TurnContext = {
    val convId = Conversation.id(s"grep-budget-${rapid.Unique()}")
    val topicId = sigil.conversation.Topic.id(s"grep-budget-topic-${rapid.Unique()}")
    val conv = Conversation(topics = List(TopicEntry(topicId, "test", "test")), _id = convId)
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
  }

  "LocalFileSystemContext.searchFiles" should {
    // (1) Pathological pattern aborts within the budget, not after minutes.
    "abort a catastrophic-backtracking pattern within a tight budget rather than hanging" in
      withTempDir(budget = 100_000L) { (fs, dir) =>
        write(dir, "victim.txt", PathologicalLine)
        val start = System.currentTimeMillis()
        fs.searchFiles(dir.toString, PathologicalPattern, glob = None, maxMatches = 100, contextLines = 0)
          .map(_ => fail("expected RegexBudgetExceededException, but searchFiles returned"))
          .handleError {
            case b: RegexBudgetExceededException =>
              Task {
                val elapsed = System.currentTimeMillis() - start
                elapsed should be < 2000L
                b.maxSteps shouldBe 100_000L
                succeed
              }
            case other => Task.error(other)
          }
      }

    // (2) Failure message is actionable.
    "raise an actionable failure naming the budget and quoting the pattern" in
      withTempDir(budget = 100_000L) { (fs, dir) =>
        write(dir, "victim.txt", PathologicalLine)
        fs.searchFiles(dir.toString, PathologicalPattern, glob = None, maxMatches = 100, contextLines = 0)
          .map(_ => fail("expected RegexBudgetExceededException"))
          .handleError {
            case b: RegexBudgetExceededException =>
              Task {
                val msg = b.getMessage
                msg should include("step")
                msg should include("budget")
                msg should include(PathologicalPattern)
                msg.toLowerCase should include("simplify")
                succeed
              }
            case other => Task.error(other)
          }
      }

    // (3) Legitimate large grep still succeeds.
    "complete a normal pattern over a large fixture tree within the default budget" in
      withTempDir(budget = LocalFileSystemContext.DefaultRegexStepBudget) { (fs, dir) =>
        (1 to 200).foreach { i =>
          write(dir, f"f$i%03d.scala", s"object Thing$i\ndef compute(x: Int): Int = x + $i\n// TODO tune $i\n")
        }
        fs.searchFiles(dir.toString, "TODO", glob = None, maxMatches = 500, contextLines = 0).map { matches =>
          matches.size shouldBe 200
          matches.map(_.content).foreach(_ should include("TODO"))
          succeed
        }
      }
  }

  "GrepTool" should {
    // (4) Tool failure releases the loop: the execute stream settles with
    // a recoverable ToolOutcome.Failure (the surface the orchestrator
    // forwards to the agent) instead of blocking.
    "settle with a recoverable failure ToolDelta when the regex blows the budget" in
      withTempDir(budget = 100_000L) { (fs, dir) =>
        write(dir, "victim.txt", PathologicalLine)
        val ctx = turnContext()
        val callId = sigil.event.Event.id()
        val start = System.currentTimeMillis()
        new GrepTool(fs)
          .execute(GrepInput(path = dir.toString, pattern = PathologicalPattern), ctx, callId)
          .toList
          .map { signals =>
            (System.currentTimeMillis() - start) should be < 2000L
            val failure = signals.collectFirst {
              case d: ToolDelta =>
                d.outcome.collect { case f: ToolOutcome.Failure => f }
            }.flatten.getOrElse(fail(s"no settling failure ToolDelta in $signals"))
            failure.recoverable shouldBe true
            failure.reason should include("budget")
            failure.reason should include(PathologicalPattern)
            succeed
          }
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
