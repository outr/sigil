package spec

import ch.epfl.scala.bsp4j.{
  BuildTargetIdentifier, CompileParams, CompileResult, Diagnostic, DiagnosticSeverity,
  Position, PublishDiagnosticsParams, Range, StatusCode, TaskId, TaskProgressParams,
  TextDocumentIdentifier
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, FiberOps, Task}
import sigil.tooling.{BspBuildConfig, BspRecordingBuildClient, BspSession, CombinedBuildServer, JsonRpcTransportException}

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Request-scoped liveness + in-flight coalescing for BSP compiles.
 * Connection-level liveness was the wrong signal: build servers
 * broadcast diagnostics from OTHER clients' builds (an editor's
 * Metals compiling the same sbt), so a compile whose response would
 * never come sat Pending forever behind chatter that had nothing to
 * do with it — and each blind re-call queued another full build.
 *
 * Verifies:
 *   1. A compile the server never answers, on a connection kept busy
 *      by FOREIGN-origin diagnostics, settles as a transport failure
 *      once its own attributable silence exhausts — terminal, with
 *      the actionable do-not-re-call framing available to the tool.
 *   2. Activity attributable to the request (its originId's
 *      diagnostics; sole-in-flight task progress) keeps a healthy
 *      long compile alive past the naive window.
 *   3. Concurrent compiles of the same targets COALESCE: one
 *      server-side build, every caller settles with its result.
 *   4. A late server response after the failure settle is discarded
 *      without a double-settle.
 */
class BspCompileLivenessSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /**
   * Reflective CombinedBuildServer stub: `buildTargetCompile` routes
   * to `onCompile`; every other method answers a completed null
   * future (never awaited by these tests).
   */
  private def stubServer(onCompile: CompileParams => CompletableFuture[CompileResult]): CombinedBuildServer = {
    val handler = new InvocationHandler {
      override def invoke(proxy: Object, method: Method, args: Array[Object]): Object =
        method.getName match {
          case "buildTargetCompile" => onCompile(args(0).asInstanceOf[CompileParams])
          case "toString" => "StubCombinedBuildServer"
          case "hashCode" => Int.box(42)
          case "equals" => java.lang.Boolean.valueOf(proxy eq args(0))
          case _ => CompletableFuture.completedFuture(null)
        }
    }
    Proxy.newProxyInstance(
      getClass.getClassLoader,
      Array(classOf[CombinedBuildServer]),
      handler
    ).asInstanceOf[CombinedBuildServer]
  }

  /**
   * Session over the stub with a spec-sized attributable-silence
   * window so the tests run in seconds.
   */
  final private class TestSession(server: CombinedBuildServer, val recording: BspRecordingBuildClient)
    extends BspSession(
      config = BspBuildConfig(projectRoot = "/tmp/bsp-liveness", command = "stub"),
      process = null.asInstanceOf[Process],
      server = server,
      client = recording
    ) {
    override protected def scopedSilenceWindow: FiniteDuration = 600.millis
  }

  private def target(uri: String) = new BuildTargetIdentifier(uri)

  private def foreignDiagnostic(client: BspRecordingBuildClient, originId: Option[String]): Unit = {
    val p = new PublishDiagnosticsParams(
      new TextDocumentIdentifier("file:///tmp/bsp-liveness/A.scala"),
      new BuildTargetIdentifier("file:///tmp/bsp-liveness/#core"),
      java.util.List.of {
        val d = new Diagnostic(new Range(new Position(0, 0), new Position(0, 1)), "boom")
        d.setSeverity(DiagnosticSeverity.ERROR)
        d
      },
      true
    )
    originId.foreach(p.setOriginId)
    client.onBuildPublishDiagnostics(p)
  }

  "request-scoped compile liveness" should {

    "settle a never-answered compile as a transport failure despite foreign-origin chatter" in {
      val client = new BspRecordingBuildClient
      // Never completes — the wedged / queued-forever request.
      val server = stubServer(_ => new CompletableFuture[CompileResult]())
      val session = new TestSession(server, client)
      // Keep the CONNECTION visibly alive the whole time with
      // diagnostics from another client's build.
      @volatile var chattering = true
      def chatter: Task[Unit] =
        if (!chattering) Task.unit
        else Task(foreignDiagnostic(client, originId = Some("metals-editor-build")))
          .flatMap(_ => Task.sleep(100.millis)).flatMap(_ => chatter)
      chatter.startUnit()
      session.compile(List(target("file:///tmp/bsp-liveness/#core"))).attempt.map { result =>
        chattering = false
        withClue(s"result=$result: ") {
          result.isFailure shouldBe true
          result.failed.get shouldBe a[JsonRpcTransportException]
        }
      }
    }

    "keep a slow compile alive on its own attributable activity" in {
      val client = new BspRecordingBuildClient
      val answered = new CompletableFuture[CompileResult]()
      val originRef = new java.util.concurrent.atomic.AtomicReference[String]("")
      val server = stubServer { params => originRef.set(params.getOriginId); answered }
      val session = new TestSession(server, client)
      // Own-origin diagnostics every 200ms — well inside the 600ms
      // window — for 2s (over 3× the naive window), then answer.
      def feed(n: Int): Task[Unit] =
        if (n <= 0) Task { answered.complete(new CompileResult(StatusCode.OK)); () }
        else Task(foreignDiagnostic(client, originId = Some(originRef.get())))
          .flatMap(_ => Task.sleep(200.millis)).flatMap(_ => feed(n - 1))
      for {
        fiber <- Task.pure(session.compile(List(target("file:///tmp/bsp-liveness/#core"))).attempt.start())
        _ <- Task.sleep(150.millis)
        _ <- feed(10)
        result <- fiber
      } yield withClue(s"result=$result: ") {
        result.isSuccess shouldBe true
        result.get.getStatusCode shouldBe StatusCode.OK
      }
    }

    "count sole-in-flight task progress as the request's own activity" in {
      val client = new BspRecordingBuildClient
      val answered = new CompletableFuture[CompileResult]()
      val server = stubServer(_ => answered)
      val session = new TestSession(server, client)
      def progress(n: Int): Task[Unit] =
        if (n <= 0) Task { answered.complete(new CompileResult(StatusCode.OK)); () }
        else Task {
          val p = new TaskProgressParams(new TaskId("t1"))
          p.setMessage(s"compiling ($n)")
          client.onBuildTaskProgress(p)
        }.flatMap(_ => Task.sleep(200.millis)).flatMap(_ => progress(n - 1))
      for {
        fiber <- Task.pure(session.compile(List(target("file:///tmp/bsp-liveness/#core"))).attempt.start())
        _ <- Task.sleep(150.millis)
        _ <- progress(10)
        result <- fiber
      } yield result.isSuccess shouldBe true
    }

    "coalesce concurrent compiles of the same targets into one server-side build" in {
      val client = new BspRecordingBuildClient
      val serverCalls = new AtomicInteger(0)
      val answered = new CompletableFuture[CompileResult]()
      val server = stubServer { _ => serverCalls.incrementAndGet(); answered }
      val session = new TestSession(server, client)
      val targets = List(target("file:///tmp/bsp-liveness/#core"))
      for {
        f1 <- Task.pure(session.compile(targets).attempt.start())
        _ <- Task.sleep(100.millis)
        f2 <- Task.pure(session.compile(targets).attempt.start())
        _ <- Task.sleep(100.millis)
        _ <- Task { answered.complete(new CompileResult(StatusCode.OK)); () }
        r1 <- f1
        r2 <- f2
      } yield withClue(s"serverCalls=${serverCalls.get()}: ") {
        serverCalls.get() shouldBe 1
        r1.isSuccess shouldBe true
        r2.isSuccess shouldBe true
      }
    }

    "discard a late response after the failure settle without double-settling" in {
      val client = new BspRecordingBuildClient
      val futures = new ConcurrentLinkedQueue[CompletableFuture[CompileResult]]()
      val server = stubServer { _ =>
        val f = new CompletableFuture[CompileResult]()
        futures.add(f)
        f
      }
      val session = new TestSession(server, client)
      session.compile(List(target("file:///tmp/bsp-liveness/#core"))).attempt.flatMap { result =>
        // Late answers arriving after the settle are inert.
        Task {
          futures.forEach { f => f.complete(new CompileResult(StatusCode.OK)); () }
        }.flatMap(_ => Task.sleep(200.millis)).map { _ =>
          result.isFailure shouldBe true
          result.failed.get shouldBe a[JsonRpcTransportException]
        }
      }
    }
  }
}
