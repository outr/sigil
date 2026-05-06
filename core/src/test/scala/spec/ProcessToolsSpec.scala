package spec

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.Message
import sigil.tool.model.{ProcessListInput, ProcessOutputInput, ProcessSignalInput, ProcessSpawnInput, ResponseContent}
import sigil.tool.process.{ProcessListTool, ProcessOutputTool, ProcessRegistry, ProcessSignalTool, ProcessSpawnTool, RingBuffer}

/**
 * End-to-end coverage for the `sigil.tool.process` family.
 * Background subprocesses are tied to a [[ProcessRegistry]]
 * instance — each test creates its own registry so handles don't
 * bleed across tests, and tears it down via `terminateAll()` in a
 * `guarantee` block.
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
      turnInput        = TurnInput(ConversationView(conversationId = convId))
    )
  }

  private def withRegistry[T](body: ProcessRegistry => Task[T]): Task[T] = Task.defer {
    val reg = new ProcessRegistry(ringBytes = 64 * 1024, terminateGraceMs = 1500L)
    body(reg).guarantee(Task(reg.terminateAll()))
  }

  private def extractJson(events: List[sigil.event.Event]): fabric.Json = {
    events.collectFirst { case m: Message =>
      m.content.collectFirst { case ResponseContent.Text(t) => t }
    }.flatten.map(JsonParser(_)).getOrElse(fabric.Obj.empty)
  }

  private def handleOf(json: fabric.Json): String = json.get("handle").map(_.asString).getOrElse("")

  /** Poll `process_output` until either the predicate satisfies or the deadline expires. */
  private def waitFor(reg: ProcessRegistry, handle: String, deadlineMs: Long)(pred: fabric.Json => Boolean): Task[fabric.Json] = {
    val tool = new ProcessOutputTool(reg)
    val tc   = turnContext(convA)
    def loop(): Task[fabric.Json] =
      tool.execute(ProcessOutputInput(handle = handle), tc).toList.flatMap { events =>
        val payload = extractJson(events)
        if (pred(payload) || System.currentTimeMillis() > deadlineMs) Task.pure(payload)
        else Task.sleep(scala.concurrent.duration.Duration(50L, "ms")).flatMap(_ => loop())
      }
    loop()
  }

  "ProcessSpawnTool" should {
    "return a handle and a positive pid" in withRegistry { reg =>
      val tc = turnContext(convA)
      new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "echo spawn-ok"), tc).toList.map { events =>
        val payload = extractJson(events)
        payload.get("handle").map(_.asString).getOrElse("") should startWith("p")
        payload.get("pid").map(_.asLong).getOrElse(0L) should be > 0L
      }
    }
  }

  "ProcessOutputTool" should {
    "stream stdout from a short-lived command" in withRegistry { reg =>
      val tc = turnContext(convA)
      val deadline = System.currentTimeMillis() + 5000L
      for {
        spawn  <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "echo hello-stream; echo more"), tc).toList
        handle = handleOf(extractJson(spawn))
        out    <- waitFor(reg, handle, deadline)(_.get("stdout").map(_.asString.contains("hello-stream")).getOrElse(false))
      } yield {
        out.get("stdout").map(_.asString.contains("hello-stream")).getOrElse(false) shouldBe true
        out.get("status").map(_.asString) should (be(Some("running")) or be(Some("exited")))
      }
    }

    "advance via sinceCursor — second read sees no duplicates" in withRegistry { reg =>
      val tc = turnContext(convA)
      val deadline = System.currentTimeMillis() + 5000L
      for {
        spawn  <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "echo first; sleep 0.2; echo second"), tc).toList
        handle = handleOf(extractJson(spawn))
        firstOut <- waitFor(reg, handle, deadline)(_.get("stdout").map(_.asString.contains("first")).getOrElse(false))
        cursor   = firstOut.get("nextCursor").map(_.asLong).getOrElse(0L)
        // Wait a moment, then read again with the cursor.
        secondOut <- new ProcessOutputTool(reg).execute(
          ProcessOutputInput(handle = handle, sinceCursor = cursor, waitForPattern = Some("second"), waitTimeoutMs = 5000L),
          tc
        ).toList.map(extractJson)
      } yield {
        secondOut.get("stdout").map(_.asString.contains("second")).getOrElse(false) shouldBe true
        // The "first" line was already consumed and shouldn't repeat.
        secondOut.get("stdout").map(_.asString.contains("first")).getOrElse(true) shouldBe false
      }
    }

    "report status = exited and an exitCode after the process ends" in withRegistry { reg =>
      val tc = turnContext(convA)
      val deadline = System.currentTimeMillis() + 5000L
      for {
        spawn  <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "exit 7"), tc).toList
        handle = handleOf(extractJson(spawn))
        out    <- waitFor(reg, handle, deadline)(_.get("status").map(_.asString.contains("exited")).getOrElse(false))
      } yield {
        out.get("status").map(_.asString) shouldBe Some("exited")
        out.get("exitCode").map(_.asInt) shouldBe Some(7)
      }
    }

    "pipe stdin to the child" in withRegistry { reg =>
      val tc = turnContext(convA)
      val deadline = System.currentTimeMillis() + 5000L
      for {
        spawn <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "cat", stdin = Some("piped-stdin\n")), tc).toList
        handle = handleOf(extractJson(spawn))
        out    <- waitFor(reg, handle, deadline)(_.get("stdout").map(_.asString.contains("piped-stdin")).getOrElse(false))
      } yield {
        out.get("stdout").map(_.asString.contains("piped-stdin")).getOrElse(false) shouldBe true
      }
    }
  }

  "ProcessSignalTool" should {
    "terminate a long-running subprocess" in withRegistry { reg =>
      val tc = turnContext(convA)
      val deadline = System.currentTimeMillis() + 5000L
      for {
        spawn   <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "sleep 30"), tc).toList
        handle   = handleOf(extractJson(spawn))
        sigOut  <- new ProcessSignalTool(reg).execute(ProcessSignalInput(handle = handle, signal = "kill"), tc).toList
        result  <- waitFor(reg, handle, deadline)(_.get("status").map(_.asString.contains("exited")).getOrElse(false))
      } yield {
        extractJson(sigOut).get("ok").map(_.asBoolean) shouldBe Some(true)
        result.get("status").map(_.asString) shouldBe Some("exited")
      }
    }
  }

  "ProcessListTool" should {
    "scope `current` to the spawning conversation" in withRegistry { reg =>
      val tcA = turnContext(convA)
      val tcB = turnContext(convB)
      for {
        _    <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "sleep 30"), tcA).toList
        _    <- new ProcessSpawnTool(reg).execute(ProcessSpawnInput(command = "sleep 30"), tcB).toList
        listA <- new ProcessListTool(reg).execute(ProcessListInput(scope = "current"), tcA).toList
        listAll <- new ProcessListTool(reg).execute(ProcessListInput(scope = "all"), tcA).toList
      } yield {
        val a   = extractJson(listA).get("processes").map(_.asVector.toList).getOrElse(Nil)
        val all = extractJson(listAll).get("processes").map(_.asVector.toList).getOrElse(Nil)
        a.size shouldBe 1
        all.size shouldBe 2
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
