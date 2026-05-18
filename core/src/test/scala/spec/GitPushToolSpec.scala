package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ContextFrame, ConversationView, Topic, TopicEntry, TurnInput}
import sigil.event.Message
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext}
import sigil.tool.git.{GitCommitTool, GitPushTool}
import sigil.tool.model.{GitCommitInput, GitPushInput, ResponseContent}

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

/**
 * Coverage for sigil bug #135 — `GitPushTool`. Spec sets up two
 * local git repos: a bare "remote" and a working clone. Drives the
 * push tool against various scenarios (default push, setUpstream
 * first-push, protected-branch force-push gating, structured error
 * classification).
 */
class GitPushToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = sigil.conversation.Conversation.id("git-push-spec")
  private val topicId = sigil.conversation.Topic.id("topic-spec")

  private def gitOnPath: Boolean = sys.env.get("PATH").exists { p =>
    p.split(java.io.File.pathSeparator).exists(d => Files.exists(Path.of(d, "git")))
  }

  /**
   * Set up: bare repo as remote, working clone with one initial
   * commit (so HEAD has something to push). `body` gets the clone's
   * FileSystemContext + path.
   */
  private def withRepoPair[T](body: (FileSystemContext, Path, Path) => Task[T]): Task[T] = Task.defer {
    val baseTmp = Files.createTempDirectory("sigil-git-push-")
    val remoteDir = baseTmp.resolve("remote.git")
    val workDir = baseTmp.resolve("work")
    Files.createDirectories(remoteDir)
    Files.createDirectories(workDir)
    val baseCtx = new LocalFileSystemContext(Some(baseTmp))
    val workCtx = new LocalFileSystemContext(Some(workDir))
    val init = for {
      _ <- baseCtx.executeCommand("git init --bare", Some(remoteDir.toString))
      _ <- workCtx.executeCommand("git init -b master", Some(workDir.toString))
      _ <- workCtx.executeCommand("git config user.email 'test@example.com'", Some(workDir.toString))
      _ <- workCtx.executeCommand("git config user.name 'Test User'", Some(workDir.toString))
      _ <- workCtx.executeCommand("git config commit.gpgsign false", Some(workDir.toString))
      _ <- workCtx.executeCommand(s"git remote add origin ${remoteDir.toString}", Some(workDir.toString))
      _ <- workCtx.writeFile("README.md", "hello")
      _ <- workCtx.executeCommand("git add -- README.md", Some(workDir.toString))
      _ <- workCtx.executeCommand("git commit -m 'init'", Some(workDir.toString))
    } yield ()

    init.flatMap(_ => body(workCtx, remoteDir, workDir)).guarantee(Task {
      val s = Files.walk(baseTmp)
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
    events.collectFirst { case tr: sigil.event.ToolResults if tr.typed.isDefined => tr.typed.get }
      .getOrElse(fabric.Obj.empty)

  if (!gitOnPath) {
    "GitPushTool" should {
      "skip when `git` is not on PATH" in pending
    }
  } else {
    "GitPushTool" should {

      "push committed changes to the configured remote with setUpstream on first push" in withRepoPair { (workCtx, remoteDir, workDir) =>
        val tool = new GitPushTool(workCtx)
        for {
          out <- tool.execute(
            GitPushInput(workingDir = Some(workDir.toString), setUpstream = true),
            turnContext()
          ).toList
        } yield {
          val payload = extractJson(out)
          withClue(s"payload = $payload: ") {
            payload.get("pushed").map(_.asBoolean) shouldBe Some(true)
            payload.get("error") shouldBe None
          }
        }
      }

      "push subsequent commits without setUpstream once tracking is established" in withRepoPair { (workCtx, _, workDir) =>
        val tool = new GitPushTool(workCtx)
        for {
          _ <- tool.execute(
            GitPushInput(workingDir = Some(workDir.toString), setUpstream = true),
            turnContext()
          ).toList
          _ <- workCtx.writeFile("second.txt", "hi")
          _ <- workCtx.executeCommand("git add -- second.txt", Some(workDir.toString))
          _ <- workCtx.executeCommand("git commit -m 'second'", Some(workDir.toString))
          out <- tool.execute(GitPushInput(workingDir = Some(workDir.toString)), turnContext()).toList
        } yield {
          val payload = extractJson(out)
          payload.get("pushed").map(_.asBoolean) shouldBe Some(true)
        }
      }

      "refuse to force-push protected branch 'master' without confirmForcePush" in withRepoPair { (workCtx, _, workDir) =>
        val tool = new GitPushTool(workCtx)
        for {
          out <- tool.execute(
            GitPushInput(
              workingDir = Some(workDir.toString),
              branch = Some("master"),
              force = true
            ),
            turnContext()
          ).toList
        } yield {
          val payload = extractJson(out)
          payload.get("error").map(_.asString).getOrElse("") should (
            include("Refusing to force-push") and include("master")
          )
          // Tool didn't actually shell out — payload has no `exitCode`,
          // since the gate short-circuits before invoking git.
          payload.get("exitCode") shouldBe None
        }
      }

      "allow force-push to protected branch when confirmForcePush = true" in withRepoPair { (workCtx, _, workDir) =>
        val tool = new GitPushTool(workCtx)
        for {
          _ <- tool.execute(
            GitPushInput(workingDir = Some(workDir.toString), setUpstream = true),
            turnContext()
          ).toList
          out <- tool.execute(
            GitPushInput(
              workingDir = Some(workDir.toString),
              branch = Some("master"),
              forceWithLease = true,
              confirmForcePush = true
            ),
            turnContext()
          ).toList
        } yield {
          val payload = extractJson(out)
          // No gate refusal — pushed (possibly a no-op fast-forward).
          payload.get("error").map(_.asString) match {
            case Some(err) =>
              err should not include "Refusing to force-push"
            case None =>
              payload.get("pushed").map(_.asBoolean) shouldBe Some(true)
          }
        }
      }

      "classify 'no upstream' as a structured error when neither setUpstream nor explicit remote is supplied" in withRepoPair {
        (workCtx, _, workDir) =>
          // Fresh branch with no upstream — default push fails.
          val tool = new GitPushTool(workCtx)
          for {
            _ <- workCtx.executeCommand("git checkout -b feature/unpushed", Some(workDir.toString))
            out <- tool.execute(GitPushInput(workingDir = Some(workDir.toString)), turnContext()).toList
          } yield {
            val payload = extractJson(out)
            payload.get("error").map(_.asString).getOrElse("") should (
              include("upstream") or include("push failed")
            )
          }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
