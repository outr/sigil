package spec

import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.ToolResults
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext}
import sigil.tool.git.{GitBranchTool, GitCommitTool, GitDiffTool, GitLogTool, GitShowTool, GitStatusTool}
import sigil.tool.model.{GitBranchInput, GitBranchOutput, GitCommitInput, GitCommitOutput, GitDiffFormat, GitDiffInput, GitDiffOutput, GitFileState, GitLogInput, GitLogOutput, GitShowInput, GitShowOutput, GitStatusInput, GitStatusOutput}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * End-to-end coverage for the `sigil.tool.git` family. Each test
 * spins up a fresh temp directory, `git init`s it, and exercises
 * one tool against the resulting repo. Skips gracefully when `git`
 * isn't on PATH (CI sandbox without git binary).
 *
 * The tools declare a typed `Output` — each test decodes the
 * `ToolResults.typed` payload back to the typed Output via the
 * Output type's registered `RW` and asserts on the typed value.
 */
class GitToolsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId  = Conversation.id("git-tools-conv")
  private val topicId = TestTopicId

  private def gitOnPath: Boolean = sys.env.get("PATH").exists { p =>
    p.split(java.io.File.pathSeparator).exists(d => Files.exists(Path.of(d, "git")))
  }

  private def withRepo[T](body: (FileSystemContext, Path) => Task[T]): Task[T] = Task.defer {
    val dir = Files.createTempDirectory("sigil-git-tools-")
    val ctx = new LocalFileSystemContext(Some(dir))
    // Initialise a quiet repo with a known identity so commit/log tests are deterministic.
    val init = for {
      _ <- ctx.executeCommand("git init -b master", Some(dir.toString))
      _ <- ctx.executeCommand("git config user.email 'test@example.com'", Some(dir.toString))
      _ <- ctx.executeCommand("git config user.name 'Test User'", Some(dir.toString))
      _ <- ctx.executeCommand("git config commit.gpgsign false", Some(dir.toString))
    } yield ()

    init.flatMap(_ => body(ctx, dir)).guarantee(Task {
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    })
  }

  private def turnContext(): TurnContext = {
    val conv = Conversation(
      topics = List(TopicEntry(topicId, "test", "test")),
      _id    = convId
    )
    TurnContext(
      sigil            = TestSigil,
      chain            = List(TestUser),
      conversation     = conv,
      turnInput        = TurnInput(ConversationView(conversationId = convId))
    )
  }

  /** Decode the `ToolResults.typed` payload back to the tool's Output
    * case class via its registered RW — what apps doing tool-to-tool
    * composition do via `Tool.invoke`. */
  private def typed[T](events: List[sigil.event.Event])(using rw: RW[T]): T = {
    val json = events.collectFirst { case t: ToolResults if t.typed.isDefined => t.typed.get }
      .getOrElse(fail(s"expected a ToolResults with a typed payload; saw: ${events.map(_.getClass.getSimpleName).mkString(", ")}"))
    rw.write(json)
  }

  private def writeAndCommit(ctx: FileSystemContext, dir: Path, file: String, content: String, message: String): Task[Unit] =
    for {
      _ <- ctx.writeFile(file, content)
      _ <- ctx.executeCommand(s"git add -- $file", Some(dir.toString))
      _ <- ctx.executeCommand(s"git commit -m '$message'", Some(dir.toString))
    } yield ()

  if (!gitOnPath) {
    "GitTools" should {
      "skip when `git` is not on PATH" in pending
    }
  } else {
    "GitStatusTool" should {
      "report a clean repo with the current branch" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _   <- writeAndCommit(ctx, dir, "README.md", "hello", "init")
          out <- new GitStatusTool(ctx).execute(GitStatusInput(workingDir = Some(dir.toString)), tc).toList
        } yield typed[GitStatusOutput](out) match {
          case GitStatusOutput.Reported(branch, _, _, entries) =>
            branch shouldBe "master"
            entries shouldBe empty
          case other => fail(s"expected Reported, got $other")
        }
      }

      "report modified working-tree files" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _   <- writeAndCommit(ctx, dir, "f.txt", "v1", "init")
          _   <- ctx.writeFile("f.txt", "v2")
          out <- new GitStatusTool(ctx).execute(GitStatusInput(workingDir = Some(dir.toString)), tc).toList
        } yield typed[GitStatusOutput](out) match {
          case GitStatusOutput.Reported(_, _, _, entries) =>
            entries.size shouldBe 1
            entries.head.path shouldBe "f.txt"
            entries.head.workingState shouldBe GitFileState.Modified
          case other => fail(s"expected Reported, got $other")
        }
      }
    }

    "GitDiffTool" should {
      "return text diff by default" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _   <- writeAndCommit(ctx, dir, "f.txt", "v1\n", "init")
          _   <- ctx.writeFile("f.txt", "v2\n")
          out <- new GitDiffTool(ctx).execute(GitDiffInput(workingDir = Some(dir.toString)), tc).toList
        } yield typed[GitDiffOutput](out) match {
          case GitDiffOutput.Text(text) => text should include("-v1")
          case other                    => fail(s"expected Text, got $other")
        }
      }

      "return structured hunks when format = hunks" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _   <- writeAndCommit(ctx, dir, "f.txt", "v1\n", "init")
          _   <- ctx.writeFile("f.txt", "v2\n")
          out <- new GitDiffTool(ctx).execute(GitDiffInput(format = GitDiffFormat.Hunks, workingDir = Some(dir.toString)), tc).toList
        } yield typed[GitDiffOutput](out) match {
          case GitDiffOutput.Hunks(hunks) =>
            hunks should not be empty
            val kinds = hunks.head.lines.map(_.kind).toSet
            kinds should contain(sigil.tool.model.GitDiffLineKind.Remove)
            kinds should contain(sigil.tool.model.GitDiffLineKind.Add)
          case other => fail(s"expected Hunks, got $other")
        }
      }
    }

    "GitLogTool" should {
      "list recent commits with sha + subject" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _   <- writeAndCommit(ctx, dir, "f.txt", "v1", "first commit")
          _   <- writeAndCommit(ctx, dir, "f.txt", "v2", "second commit")
          out <- new GitLogTool(ctx).execute(GitLogInput(limit = Some(5), workingDir = Some(dir.toString)), tc).toList
        } yield typed[GitLogOutput](out) match {
          case GitLogOutput.Listed(commits) =>
            commits.size shouldBe 2
            commits.head.subject shouldBe "second commit"
            commits.last.subject shouldBe "first commit"
            commits.head.sha.length should be > 7
          case other => fail(s"expected Listed, got $other")
        }
      }
    }

    "GitBranchTool" should {
      "identify the current branch" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _   <- writeAndCommit(ctx, dir, "f.txt", "v1", "init")
          out <- new GitBranchTool(ctx).execute(GitBranchInput(workingDir = Some(dir.toString)), tc).toList
        } yield typed[GitBranchOutput](out) match {
          case GitBranchOutput.Listed(current, branches) =>
            current shouldBe "master"
            branches.exists(_.isCurrent) shouldBe true
          case other => fail(s"expected Listed, got $other")
        }
      }
    }

    "GitShowTool" should {
      "render HEAD with subject + author + sha" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _   <- writeAndCommit(ctx, dir, "f.txt", "v1", "first commit")
          out <- new GitShowTool(ctx).execute(GitShowInput(sha = "HEAD", workingDir = Some(dir.toString)), tc).toList
        } yield typed[GitShowOutput](out) match {
          case GitShowOutput.Found(sha, author, _, subject, _, _) =>
            subject shouldBe "first commit"
            author shouldBe "Test User"
            sha should not be empty
          case other => fail(s"expected Found, got $other")
        }
      }
    }

    "GitCommitTool" should {
      "stage and commit, returning the sha" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _   <- writeAndCommit(ctx, dir, "seed.txt", "seed", "init")
          _   <- ctx.writeFile("new.txt", "fresh")
          _   <- ctx.executeCommand("git add new.txt", Some(dir.toString))
          out <- new GitCommitTool(ctx).execute(GitCommitInput(message = "Add new.txt", workingDir = Some(dir.toString)), tc).toList
        } yield typed[GitCommitOutput](out) match {
          case GitCommitOutput.Committed(sha, message) =>
            sha.length should be > 7
            message shouldBe "Add new.txt"
          case other => fail(s"expected Committed, got $other")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
