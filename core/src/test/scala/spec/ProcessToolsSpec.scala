package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.model.{ProcessListInput, ProcessListOutput, ProcessListScope, ProcessOutputInput, ProcessOutputResult, ProcessRunStatus, ProcessSignal, ProcessSignalInput, ProcessSignalOutput, ProcessSpawnInput, ProcessSpawnOutput}
import sigil.tool.process.{ProcessListTool, ProcessOutputTool, ProcessRegistry, ProcessSignalTool, ProcessSpawnTool, RingBuffer}

import scala.reflect.ClassTag
import sigil.event.Event

/**
 * End-to-end coverage for the `sigil.tool.process` family.
 * Background subprocesses are tied to a [[ProcessRegistry]]
 * instance — each test creates its own registry so handles don't
 * bleed across tests, and tears it down via `terminateAll()` in a
 * `guarantee` block.
 *
 * Each process tool declares a typed `Output`; the framework folds
 * it onto the settling [[sigil.signal.ToolDelta]]'s `output` field,
 * and each test decodes that payload back to the typed Output via
 * its registered `RW`.
 */
class ProcessToolsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convA = Conversation.id("proc-conv-a")
  private val convB = Conversation.id("proc-conv-b")

  private def turnContext(convId: lightdb.id.Id[Conversation]): TurnContext = {
    val conv = Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      _id    = convId
    )
    TurnContext(
      sigil            = TestSigil,
      chain            = List(TestUser),
      conversation     = conv,
      turnInput        = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
  }

  private def withRegistry[T](body: ProcessRegistry => Task[T]): Task[T] = Task.defer {
    val reg = new ProcessRegistry(ringBytes = 64 * 1024, terminateGraceMs = 1500L)
    body(reg).guarantee(Task(reg.terminateAll()))
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

  /** Poll `process_output` until either the predicate satisfies or the deadline expires. */
  private def waitFor(reg: ProcessRegistry, handle: String, deadlineMs: Long)(pred: ProcessOutputResult => Boolean): Task[ProcessOutputResult] = {
    val tool = new ProcessOutputTool(reg)
    val tc   = turnContext(convA)
    def loop(): Task[ProcessOutputResult] =
      tool.execute(ProcessOutputInput(handle = handle), tc, Event.id()).toList.flatMap { signals =>
        val result = typed[ProcessOutputResult](signals)
        if (pred(result) || System.currentTimeMillis() > deadlineMs) Task.pure(result)
        else Task.sleep(scala.concurrent.duration.Duration(50L, "ms")).flatMap(_ => loop())
      }
    loop()
  }

  "ProcessSpawnTool" should {
    "return a handle and a positive pid" in withRegistry { reg =>
      val tc = turnContext(convA)
      new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "echo spawn-ok"), tc, Event.id()).toList.map { events =>
        val out = typed[ProcessSpawnOutput](events)
        out.handle should startWith("p")
        out.pid should be > 0L
      }
    }
  }

  "ProcessOutputTool" should {
    "stream stdout from a short-lived command" in withRegistry { reg =>
      val tc = turnContext(convA)
      val deadline = System.currentTimeMillis() + 5000L
      for {
        spawn  <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "echo hello-stream; echo more"), tc, Event.id()).toList
        handle  = typed[ProcessSpawnOutput](spawn).handle
        out    <- waitFor(reg, handle, deadline)(_.stdout.contains("hello-stream"))
      } yield {
        out.stdout should include("hello-stream")
        out.status should (be(ProcessRunStatus.Running) or be(ProcessRunStatus.Exited))
      }
    }

    "advance via sinceCursor — second read sees no duplicates" in withRegistry { reg =>
      val tc = turnContext(convA)
      val deadline = System.currentTimeMillis() + 5000L
      for {
        spawn    <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "echo first; sleep 0.2; echo second"), tc, Event.id()).toList
        handle    = typed[ProcessSpawnOutput](spawn).handle
        firstOut <- waitFor(reg, handle, deadline)(_.stdout.contains("first"))
        cursor    = firstOut.nextCursor
        // Wait a moment, then read again with the cursor.
        secondOut <- new ProcessOutputTool(reg).execute(
          ProcessOutputInput(handle = handle, sinceCursor = cursor, waitForPattern = Some("second"), waitTimeoutMs = 5000L),
          tc, Event.id()
        ).toList.map(typed[ProcessOutputResult])
      } yield {
        secondOut.stdout should include("second")
        // The "first" line was already consumed and shouldn't repeat.
        secondOut.stdout should not include "first"
      }
    }

    "report status = exited and an exitCode after the process ends" in withRegistry { reg =>
      val tc = turnContext(convA)
      val deadline = System.currentTimeMillis() + 5000L
      for {
        spawn  <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "exit 7"), tc, Event.id()).toList
        handle  = typed[ProcessSpawnOutput](spawn).handle
        out    <- waitFor(reg, handle, deadline)(_.status == ProcessRunStatus.Exited)
      } yield {
        out.status shouldBe ProcessRunStatus.Exited
        out.exitCode shouldBe Some(7)
      }
    }

    "pipe stdin to the child" in withRegistry { reg =>
      val tc = turnContext(convA)
      val deadline = System.currentTimeMillis() + 5000L
      for {
        spawn  <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "cat", stdin = Some("piped-stdin\n")), tc, Event.id()).toList
        handle  = typed[ProcessSpawnOutput](spawn).handle
        out    <- waitFor(reg, handle, deadline)(_.stdout.contains("piped-stdin"))
      } yield out.stdout should include("piped-stdin")
    }
  }

  "ProcessSignalTool" should {
    "terminate a long-running subprocess" in withRegistry { reg =>
      val tc = turnContext(convA)
      val deadline = System.currentTimeMillis() + 5000L
      for {
        spawn  <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "sleep 30"), tc, Event.id()).toList
        handle  = typed[ProcessSpawnOutput](spawn).handle
        sigOut <- new ProcessSignalTool(reg).execute(ProcessSignalInput(handle = handle, signal = ProcessSignal.Kill), tc, Event.id()).toList
        result <- waitFor(reg, handle, deadline)(_.status == ProcessRunStatus.Exited)
      } yield {
        typed[ProcessSignalOutput](sigOut).delivered shouldBe true
        result.status shouldBe ProcessRunStatus.Exited
      }
    }
  }

  "ProcessListTool" should {
    "scope `current` to the spawning conversation" in withRegistry { reg =>
      val tcA = turnContext(convA)
      val tcB = turnContext(convB)
      for {
        _       <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "sleep 30"), tcA, Event.id()).toList
        _       <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "sleep 30"), tcB, Event.id()).toList
        listA   <- new ProcessListTool(reg).execute(ProcessListInput(scope = ProcessListScope.Current), tcA, Event.id()).toList
        listAll <- new ProcessListTool(reg).execute(ProcessListInput(scope = ProcessListScope.All), tcA, Event.id()).toList
      } yield {
        typed[ProcessListOutput](listA).processes.size shouldBe 1
        typed[ProcessListOutput](listAll).processes.size shouldBe 2
      }
    }
  }

  "RingBuffer" should {
    "report `dropped` when reading from a cursor that scrolled past" in {
      val buf = new RingBuffer(maxBytes = 16)
      buf.append("0123456789")                       // 10 bytes — fits
      buf.append("ABCDEFGHIJ")                       // 20 total written; first 4 scroll out
      val (text, cursor, dropped) = buf.readSince(0L)
      text shouldBe "456789ABCDEFGHIJ"               // last 16 bytes retained
      cursor shouldBe 20L
      dropped shouldBe true                          // cursor 0 < dropped count (4)
      Task.pure(succeed)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
