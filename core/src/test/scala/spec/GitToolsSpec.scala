package spec

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.Message
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext}
import sigil.tool.git.{GitBranchTool, GitCommitTool, GitDiffTool, GitLogTool, GitShowTool, GitStatusTool}
import sigil.tool.model.{GitBranchInput, GitCommitInput, GitDiffInput, GitLogInput, GitShowInput, GitStatusInput, ResponseContent}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * End-to-end coverage for the `sigil.tool.git` family. Each test
 * spins up a fresh temp directory, `git init`s it, and exercises
 * one tool against the resulting repo. Skips gracefully when `git`
 * isn't on PATH (CI sandbox without git binary).
 */
class GitToolsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("git-tools-conv")
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
      _id = convId
    )
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      turnInput = TurnInput(ConversationView(conversationId = convId))
    )
  }

  private def extractJson(events: List[sigil.event.Event]): fabric.Json =
    // Bug #134 — FsToolEmit now emits ToolResults with the typed
    // payload in `typed` instead of a Message with JSON-stringified
    // text. Pull from `typed` first; fall back to the legacy
    // Message-Text path for any tool still on the old shape.
    events.collectFirst { case tr: sigil.event.ToolResults if tr.typed.isDefined => tr.typed.get }
      .orElse(
        events.collectFirst { case m: Message =>
          m.content.collectFirst { case ResponseContent.Text(t) => t }
        }.flatten.map(JsonParser(_))
      )
      .getOrElse(fabric.Obj.empty)

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
          _ <- writeAndCommit(ctx, dir, "README.md", "hello", "init")
          out <- new GitStatusTool(ctx).execute(GitStatusInput(workingDir = Some(dir.toString)), tc).toList
        } yield {
          val payload = extractJson(out)
          payload.get("branch").map(_.asString) shouldBe Some("master")
          payload.get("entries").map(_.asVector.toList.size) shouldBe Some(0)
        }
      }

      "report modified working-tree files" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _ <- writeAndCommit(ctx, dir, "f.txt", "v1", "init")
          _ <- ctx.writeFile("f.txt", "v2")
          out <- new GitStatusTool(ctx).execute(GitStatusInput(workingDir = Some(dir.toString)), tc).toList
        } yield {
          val payload = extractJson(out)
          val entries = payload.get("entries").map(_.asVector.toList).getOrElse(Nil)
          entries.size shouldBe 1
          entries.head.get("path").map(_.asString) shouldBe Some("f.txt")
          entries.head.get("workingState").map(_.asString) shouldBe Some("M")
        }
      }
    }

    "GitDiffTool" should {
      "return text diff by default" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _ <- writeAndCommit(ctx, dir, "f.txt", "v1\n", "init")
          _ <- ctx.writeFile("f.txt", "v2\n")
          out <- new GitDiffTool(ctx).execute(GitDiffInput(workingDir = Some(dir.toString)), tc).toList
        } yield {
          val payload = extractJson(out)
          payload.get("text").map(_.asString.contains("-v1")).getOrElse(false) shouldBe true
        }
      }

      "return structured hunks when format = hunks" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _ <- writeAndCommit(ctx, dir, "f.txt", "v1\n", "init")
          _ <- ctx.writeFile("f.txt", "v2\n")
          out <- new GitDiffTool(ctx).execute(GitDiffInput(format = "hunks", workingDir = Some(dir.toString)), tc).toList
        } yield {
          val payload = extractJson(out)
          val hunks = payload.get("hunks").map(_.asVector.toList).getOrElse(Nil)
          hunks should not be empty
          val lines = hunks.head.get("lines").map(_.asVector.toList).getOrElse(Nil)
          val kinds = lines.flatMap(_.get("kind").map(_.asString)).toSet
          kinds should contain("remove")
          kinds should contain("add")
        }
      }
    }

    "GitLogTool" should {
      "list recent commits with sha + subject" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _ <- writeAndCommit(ctx, dir, "f.txt", "v1", "first commit")
          _ <- writeAndCommit(ctx, dir, "f.txt", "v2", "second commit")
          out <- new GitLogTool(ctx).execute(GitLogInput(limit = Some(5), workingDir = Some(dir.toString)), tc).toList
        } yield {
          val commits = extractJson(out).get("commits").map(_.asVector.toList).getOrElse(Nil)
          commits.size shouldBe 2
          commits.head.get("subject").map(_.asString) shouldBe Some("second commit")
          commits.last.get("subject").map(_.asString) shouldBe Some("first commit")
          commits.head.get("sha").map(_.asString.length).getOrElse(0) should be > 7
        }
      }
    }

    "GitBranchTool" should {
      "identify the current branch" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _ <- writeAndCommit(ctx, dir, "f.txt", "v1", "init")
          out <- new GitBranchTool(ctx).execute(GitBranchInput(workingDir = Some(dir.toString)), tc).toList
        } yield {
          val payload = extractJson(out)
          payload.get("current").map(_.asString) shouldBe Some("master")
          val branches = payload.get("branches").map(_.asVector.toList).getOrElse(Nil)
          branches.exists(_.get("isCurrent").map(_.asBoolean).contains(true)) shouldBe true
        }
      }
    }

    "GitShowTool" should {
      "render HEAD with subject + author + sha" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _ <- writeAndCommit(ctx, dir, "f.txt", "v1", "first commit")
          out <- new GitShowTool(ctx).execute(GitShowInput(sha = "HEAD", workingDir = Some(dir.toString)), tc).toList
        } yield {
          val payload = extractJson(out)
          payload.get("subject").map(_.asString) shouldBe Some("first commit")
          payload.get("author").map(_.asString) shouldBe Some("Test User")
          payload.get("sha").map(_.asString.nonEmpty).getOrElse(false) shouldBe true
        }
      }
    }

    "GitCommitTool" should {
      "stage and commit, returning the sha" in withRepo { (ctx, dir) =>
        val tc = turnContext()
        for {
          _ <- writeAndCommit(ctx, dir, "seed.txt", "seed", "init")
          _ <- ctx.writeFile("new.txt", "fresh")
          _ <- ctx.executeCommand("git add new.txt", Some(dir.toString))
          out <- new GitCommitTool(ctx).execute(GitCommitInput(message = "Add new.txt", workingDir = Some(dir.toString)), tc).toList
        } yield {
          val payload = extractJson(out)
          payload.get("success").map(_.asBoolean) shouldBe Some(true)
          payload.get("sha").map(_.asString.length).getOrElse(0) should be > 7
          payload.get("message").map(_.asString) shouldBe Some("Add new.txt")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
