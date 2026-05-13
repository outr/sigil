package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AsyncWordSpec}
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.event.{Message, MessageRole, MessageVisibility, ToolResults}
import sigil.signal.EventState
import sigil.tool.core.{NoResponseTool, RespondCardTool, RespondCardsTool, RespondFailureTool, RespondFieldTool, RespondOptionsTool, RespondTool}
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolFailureException, ToolInput, ToolName, ToolResult, TypedOutputTool}

import scala.concurrent.duration.*

/** Shared ad-hoc input for synchronous annotation tests. */
private case class PlainInput() extends ToolInput derives RW

/**
 * Coverage for sigil bug #131 — Sigil-native tools adopt the MCP
 * `CallToolResult` convention: `ToolResult[O] = Success(value) |
 * Failure(message, hint, args)`. `TypedOutputTool` default-wraps
 * legacy `executeTyped` (success → Success; thrown error →
 * Failure(message=e.getMessage, args=input.toJson)). New tools opt
 * into the envelope by overriding `executeTypedResult` directly.
 *
 * Plus annotation surface: `Tool` exposes `readOnly`, `destructive`,
 * `idempotent`, `openWorld` defaults; the respond-family tools set
 * `destructive = true`; `wireDescription` prefixes destructive tools'
 * descriptions on the wire with `**ENDS YOUR TURN.**`.
 */
class ToolResultEnvelopeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private case class EchoInput(payload: String = "") extends ToolInput derives RW
  private case class EchoOutput(echoed: String) derives RW

  ToolInput.register(RW.static(EchoInput()))

  /** Legacy-shaped tool — overrides only `executeTyped`. Drives the
    * default-wrap path: success → Success; throw → Failure. */
  private final class LegacyEchoTool(throwOn: Option[String] = None)
    extends TypedOutputTool[EchoInput, EchoOutput](
      name = ToolName("legacy_echo"),
      description = "Echoes the payload."
    ) {
    override protected def executeTyped(input: EchoInput, context: TurnContext): Task[EchoOutput] =
      throwOn match {
        case Some(msg) => Task.error(new RuntimeException(msg))
        case None      => Task.pure(EchoOutput(input.payload))
      }
  }

  /** Envelope-aware tool — overrides `executeTypedResult` directly.
    * Drives the explicit-Failure path. */
  private final class EnvelopeEchoTool extends TypedOutputTool[EchoInput, EchoOutput](
    name = ToolName("envelope_echo"),
    description = "Echoes the payload via the envelope."
  ) {
    override protected def executeTypedResult(input: EchoInput, context: TurnContext): Task[ToolResult[EchoOutput]] =
      if (input.payload.isEmpty)
        Task.pure(ToolResult.failure(
          message = "payload must not be empty",
          hint    = Some("set `payload` to a non-empty string"),
          args    = Some(s"payload.length=0")
        ))
      else Task.pure(ToolResult.success(EchoOutput(input.payload)))
  }

  private def turnContext(): Task[TurnContext] =
    TestSigil.curate(Conversation.id("envelope-spec"), sigil.db.Model.id("test", "envelope"), List(TestUser)).map { ti =>
      TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser),
        conversation = Conversation(topics = TestTopicStack, participants = Nil, _id = Conversation.id("envelope-spec")),
        turnInput    = ti
      )
    }

  "TypedOutputTool default wrap" should {

    "produce ToolResult.Success carrying the typed payload when executeTyped succeeds" in {
      val tool = new LegacyEchoTool()
      turnContext().flatMap { ctx =>
        tool.invoke(EchoInput("hello"), ctx).map { out =>
          out.echoed shouldBe "hello"
        }
      }
    }

    "auto-convert a thrown Task.error to ToolResult.Failure (raised as ToolFailureException by invoke)" in {
      val tool = new LegacyEchoTool(throwOn = Some("kaboom"))
      turnContext().flatMap { ctx =>
        tool.invoke(EchoInput("hello"), ctx)
          .map(_ => fail("expected ToolFailureException"))
          .handleError { err =>
            err shouldBe a [ToolFailureException]
            val tfe = err.asInstanceOf[ToolFailureException]
            tfe.toolName shouldBe ToolName("legacy_echo")
            tfe.failureMessage shouldBe "kaboom"
            // Failing input is captured as JSON args.
            tfe.args.get should include("hello")
            Task.pure(succeed)
          }
      }
    }
  }

  "TypedOutputTool envelope-aware tools" should {

    "return ToolResult.Failure with hint when executeTypedResult emits it explicitly" in {
      val tool = new EnvelopeEchoTool
      turnContext().flatMap { ctx =>
        tool.invoke(EchoInput(""), ctx)
          .map(_ => fail("expected ToolFailureException"))
          .handleError { err =>
            err shouldBe a [ToolFailureException]
            val tfe = err.asInstanceOf[ToolFailureException]
            tfe.failureMessage should include("payload must not be empty")
            tfe.hint.get should include("non-empty")
            Task.pure(succeed)
          }
      }
    }

    "emit a Tool-role Failure-disposition Message on the event stream when execute runs against a Failure" in {
      val tool = new EnvelopeEchoTool
      turnContext().flatMap { ctx =>
        tool.execute(EchoInput(""), ctx).toList.map { evs =>
          val msgs = evs.collect { case m: Message => m }
          msgs should have size 1
          msgs.head.role shouldBe MessageRole.Tool
          msgs.head.visibility shouldBe MessageVisibility.Agents
          msgs.head.isFailure shouldBe true
          val reason = msgs.head.failureReason.getOrElse("")
          reason should include("payload must not be empty")
          reason should include("non-empty") // hint inlined
        }
      }
    }

    "emit a Tool-role ToolResults event on the event stream when execute runs against a Success" in {
      val tool = new EnvelopeEchoTool
      turnContext().flatMap { ctx =>
        tool.execute(EchoInput("hi"), ctx).toList.map { evs =>
          val results = evs.collect { case tr: ToolResults => tr }
          results should have size 1
          results.head.typed.isDefined shouldBe true
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

/**
 * Synchronous coverage for the annotation surface — Tool defaults
 * and the respond-family migration to `destructive = true` +
 * `wireDescription` prefix.
 */
class ToolAnnotationsSpec extends AnyWordSpec with Matchers {

  "Tool annotations" should {

    "default to safe (all false) on the base trait" in {
      // Drive via an inline ad-hoc tool whose only declaration is the
      // base trait — every annotation must read as `false` so apps
      // that don't opt in get no behavior change.
      val noop = new sigil.tool.Tool {
        override def name = ToolName("plain")
        override def description = "noop"
        override def inputRW = summon[fabric.rw.RW[PlainInput]]
        override def execute(input: ToolInput, context: TurnContext) = rapid.Stream.empty
      }
      noop.readOnly shouldBe false
      noop.destructive shouldBe false
      noop.idempotent shouldBe false
      noop.openWorld shouldBe false
    }
  }

  "Respond-family tools" should {
    "all set destructive = true" in {
      val deprecatedFamily: List[sigil.tool.Tool] =
        (List(RespondOptionsTool, RespondFieldTool, RespondFailureTool): @annotation.nowarn("cat=deprecation"))
      val activeFamily: List[sigil.tool.Tool] =
        List(RespondTool, RespondCardTool, RespondCardsTool, NoResponseTool)
      (activeFamily ++ deprecatedFamily).foreach { t =>
        withClue(s"${t.name.value} should be destructive: ") {
          t.destructive shouldBe true
        }
      }
    }

    "render the **ENDS YOUR TURN.** prefix via wireDescription" in {
      // Need a Sigil + Mode to render; use the framework's defaults.
      val mode = sigil.provider.ConversationMode
      val rendered = RespondTool.wireDescription(mode, TestSigil)
      rendered should startWith("**ENDS YOUR TURN.**")
    }
  }

  "Filesystem / shell destructive tools" should {
    "advertise destructive = true via the DestructiveExternalTool mix-in" in {
      // BashTool / EditFileTool / WriteFileTool / DeleteFileTool —
      // all real instances need a FileSystemContext arg; instantiate
      // a stub and assert the annotation.
      val ctx = sigil.tool.fs.LocalFileSystemContext()
      List[sigil.tool.Tool](
        new sigil.tool.fs.BashTool(ctx),
        new sigil.tool.fs.EditFileTool(ctx),
        new sigil.tool.fs.WriteFileTool(ctx),
        new sigil.tool.fs.DeleteFileTool(ctx)
      ).foreach { t =>
        withClue(s"${t.name.value} should be destructive: ") {
          t.destructive shouldBe true
        }
      }
    }
  }

  "Read-only filesystem tools" should {
    "advertise readOnly = true via the ReadOnlyExternalTool mix-in" in {
      val ctx = sigil.tool.fs.LocalFileSystemContext()
      List[sigil.tool.Tool](
        new sigil.tool.fs.ReadFileTool(ctx),
        new sigil.tool.fs.GrepTool(ctx),
        new sigil.tool.fs.GlobTool(ctx)
      ).foreach { t =>
        withClue(s"${t.name.value} should be read-only: ") {
          t.readOnly shouldBe true
          t.destructive shouldBe false
        }
      }
    }
  }
}
