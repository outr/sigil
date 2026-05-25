package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext}
import sigil.tool.git.GitPushTool
import sigil.tool.model.{GitPushError, GitPushInput, GitPushOutput}

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import sigil.event.Event

/**
 * Coverage for `GitPushTool`. Spec sets up two local git repos: a
 * bare "remote" and a working clone. Drives the push tool against
 * various scenarios (default push, setUpstream first-push,
 * protected-branch force-push gating, structured error
 * classification) and asserts on the typed [[GitPushOutput]].
 */
class GitPushToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId  = sigil.conversation.Conversation.id("git-push-spec")
  private val topicId = sigil.conversation.Topic.id("topic-spec")

  private def gitOnPath: Boolean = sys.env.get("PATH").exists { p =>
    p.split(java.io.File.pathSeparator).exists(d => Files.exists(Path.of(d, "git")))
  }

  /** Set up: bare repo as remote, working clone with one initial
    * commit (so HEAD has something to push). `body` gets the clone's
    * FileSystemContext + path. */
  private def withRepoPair[T](body: (FileSystemContext, Path, Path) => Task[T]): Task[T] = Task.defer {
    val baseTmp   = Files.createTempDirectory("sigil-git-push-")
    val remoteDir = baseTmp.resolve("remote.git")
    val workDir   = baseTmp.resolve("work")
    Files.createDirectories(remoteDir)
    Files.createDirectories(workDir)
    val baseCtx   = new LocalFileSystemContext(Some(baseTmp))
    val workCtx   = new LocalFileSystemContext(Some(workDir))
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
      _id    = convId
    )
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser),
      conversation = conv,
      turnInput    = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
  }

  /** Recover the typed payload from the settling [[ToolDelta]]'s
    * `output` — the same concrete instance the tool produced, no
    * JSON round-trip. */
  private def typed[T <: sigil.tool.ToolOutput](signals: List[Signal])(using ct: ClassTag[T]): T =
    signals.collectFirst {
      case d: ToolDelta if d.output.exists(o => ct.runtimeClass.isInstance(o)) =>
        d.output.get.asInstanceOf[T]
    }.getOrElse(fail(
      s"expected ToolOutput of type ${ct.runtimeClass.getSimpleName}; saw outputs: " +
        signals.collect { case d: ToolDelta => d.output.map(_.getClass.getSimpleName) }.mkString(", ")
    ))

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
            turnContext(), Event.id()
          ).toList
        } yield typed[GitPushOutput](out) match {
          case _: GitPushOutput.Pushed => succeed
          case other                   => fail(s"expected Pushed, got $other")
        }
      }

      "push subsequent commits without setUpstream once tracking is established" in withRepoPair { (workCtx, _, workDir) =>
        val tool = new GitPushTool(workCtx)
        for {
          _   <- tool.execute(
                   GitPushInput(workingDir = Some(workDir.toString), setUpstream = true),
                   turnContext(), Event.id()
                 ).toList
          _   <- workCtx.writeFile("second.txt", "hi")
          _   <- workCtx.executeCommand("git add -- second.txt", Some(workDir.toString))
          _   <- workCtx.executeCommand("git commit -m 'second'", Some(workDir.toString))
          out <- tool.execute(GitPushInput(workingDir = Some(workDir.toString)), turnContext(), Event.id()).toList
        } yield typed[GitPushOutput](out) match {
          case _: GitPushOutput.Pushed => succeed
          case other                   => fail(s"expected Pushed, got $other")
        }
      }

      "refuse to force-push protected branch 'master' without confirmForcePush" in withRepoPair { (workCtx, _, workDir) =>
        val tool = new GitPushTool(workCtx)
        for {
          out <- tool.execute(
            GitPushInput(
              workingDir = Some(workDir.toString),
              branch     = Some("master"),
              force      = true
            ),
            turnContext(), Event.id()
          ).toList
        } yield typed[GitPushOutput](out) match {
          case GitPushOutput.Failed(error, detail, exitCode, _) =>
            error shouldBe GitPushError.ForcePushBlocked
            detail should (include("Refusing to force-push") and include("master"))
            // Tool didn't shell out — the gate short-circuits before invoking git.
            exitCode shouldBe None
          case other => fail(s"expected Failed(ForcePushBlocked), got $other")
        }
      }

      "allow force-push to protected branch when confirmForcePush = true" in withRepoPair { (workCtx, _, workDir) =>
        val tool = new GitPushTool(workCtx)
        for {
          _   <- tool.execute(
                   GitPushInput(workingDir = Some(workDir.toString), setUpstream = true),
                   turnContext(), Event.id()
                 ).toList
          out <- tool.execute(
            GitPushInput(
              workingDir       = Some(workDir.toString),
              branch           = Some("master"),
              forceWithLease   = true,
              confirmForcePush = true
            ),
            turnContext(), Event.id()
          ).toList
        } yield typed[GitPushOutput](out) match {
          // No gate refusal — pushed (possibly a no-op fast-forward).
          case _: GitPushOutput.Pushed => succeed
          case GitPushOutput.Failed(error, _, _, _) =>
            error should not be GitPushError.ForcePushBlocked
        }
      }

      "classify 'no upstream' as a structured error when neither setUpstream nor explicit remote is supplied" in withRepoPair { (workCtx, _, workDir) =>
        // Fresh branch with no upstream — default push fails.
        val tool = new GitPushTool(workCtx)
        for {
          _   <- workCtx.executeCommand("git checkout -b feature/unpushed", Some(workDir.toString))
          out <- tool.execute(GitPushInput(workingDir = Some(workDir.toString)), turnContext(), Event.id()).toList
        } yield typed[GitPushOutput](out) match {
          case GitPushOutput.Failed(error, _, _, _) =>
            error should (be(GitPushError.NoUpstream) or be(GitPushError.Unknown))
          case other => fail(s"expected Failed, got $other")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
