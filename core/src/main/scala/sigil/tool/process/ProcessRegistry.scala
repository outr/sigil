package sigil.tool.process

import lightdb.id.Id
import rapid.Task
import sigil.conversation.Conversation
import sigil.tool.model.ProcessSignal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory registry of background subprocesses spawned by the
 * `process_*` tools. The registry survives the JVM but not a
 * restart — handles are not persisted. JVM shutdown sends SIGTERM
 * to every live entry; entries that don't exit within `terminateGraceMs`
 * are SIGKILL'd.
 *
 * Apps wiring [[sigil.tool.process.ProcessSpawnTool]] etc. inject
 * one registry instance and reuse it across all process tools so
 * the in-memory map is shared.
 *
 * @param ringBytes         per-stream output retention before bytes
 *                          scroll out the front of the ring buffer
 *                          (default 1 MB).
 * @param terminateGraceMs  ms a SIGTERM has to settle before SIGKILL
 *                          fires during shutdown (default 5 s).
 */
class ProcessRegistry(val ringBytes: Int = 1024 * 1024,
                      val terminateGraceMs: Long = 5000L) {

  private val entries  = new ConcurrentHashMap[String, ProcessEntry]()
  private val sequence = new AtomicLong(0L)
  // Belt-and-suspenders: any process the user forgot to terminate
  // gets SIGTERM (then SIGKILL) on JVM exit. Apps that drive their
  // own ordered shutdown call `terminateAll` explicitly first.
  // Wrapped in a top-level try/catch — sbt's forked-test runner
  // tears down its child ClassLoader before this hook fires, which
  // can throw NoClassDefFoundError on the inner method dispatch.
  // Swallowing keeps the JVM exit clean.
  private val shutdownHook = new Thread(() => {
    try terminateAll()
    catch { case _: Throwable => () }
  }, "sigil-process-registry-shutdown")
  Runtime.getRuntime.addShutdownHook(shutdownHook)

  /** Spawn a fresh subprocess. Caller-facing handle id is short
    * (`p1`, `p2`, …) so agents can name them in subsequent calls. */
  def spawn(command: String,
            workingDir: Option[String] = None,
            env: Map[String, String] = Map.empty,
            stdin: Option[String] = None,
            conversationId: Id[Conversation]): Task[ProcessHandle] = Task {
    val id = s"p${sequence.incrementAndGet()}"
    val entry = ProcessEntry.spawn(command, workingDir, env, stdin, id, conversationId, ringBytes)
    entries.put(id, entry)
    entry.handle
  }

  /** Read accumulated output since `sinceCursor`. When `waitForLines`
    * or `waitForPattern` is set, the call polls (50 ms) until the
    * predicate is satisfied or `waitTimeoutMs` elapses. */
  def output(handle: String,
             sinceCursor: Long = 0L,
             waitForLines: Option[Int] = None,
             waitForPattern: Option[String] = None,
             waitTimeoutMs: Long = 0L): Task[ProcessOutput] = Task {
    val entry = entries.get(handle)
    if (entry == null) throw new NoSuchElementException(s"No process handle: $handle")
    val deadline = System.currentTimeMillis() + waitTimeoutMs
    val regex    = waitForPattern.map(_.r)

    @scala.annotation.tailrec
    def loop(): ProcessOutput = {
      val (out, outCursor, outDropped) = entry.stdout.readSince(sinceCursor)
      val (err, errCursor, errDropped) = entry.stderr.readSince(sinceCursor)
      val status                       = entry.status
      val combinedNext                 = math.max(outCursor, errCursor)
      val haveLines = waitForLines.exists(n => out.count(_ == '\n') + err.count(_ == '\n') >= n)
      val havePat   = regex.exists(r => r.findFirstIn(out).isDefined || r.findFirstIn(err).isDefined)
      val noWait    = waitForLines.isEmpty && waitForPattern.isEmpty
      val ready     = noWait || haveLines || havePat || status != ProcessStatus.Running
      if (ready || System.currentTimeMillis() >= deadline)
        ProcessOutput(handle, out, err, sinceCursor, combinedNext, status, entry.exitCode, outDropped || errDropped)
      else {
        Thread.sleep(50L)
        loop()
      }
    }
    loop()
  }

  /** Send a signal. [[ProcessSignal.Terminate]] sends SIGTERM with
    * grace then SIGKILL; [[ProcessSignal.Interrupt]] is a
    * best-effort SIGINT via `Process.destroy`; [[ProcessSignal.Kill]]
    * is an immediate SIGKILL.
    *
    * Throws [[NoSuchElementException]] when the handle is unknown (the
    * caller distinguishes that from a delivery outcome). Returns
    * `true` when a live process was actually signaled, `false` when
    * the process was already dead (nothing delivered). */
  def signal(handle: String, signal: ProcessSignal): Task[Boolean] = Task {
    val entry = entries.get(handle)
    if (entry == null) throw new NoSuchElementException(s"No process handle: $handle")
    else
      signal match {
        case ProcessSignal.Terminate => entry.terminate(terminateGraceMs)
        case ProcessSignal.Interrupt => entry.terminate(terminateGraceMs)
        case ProcessSignal.Kill      => entry.kill()
      }
  }

  /** List handles. `filterByConversation = Some(convId)` restricts to
    * the spawning conversation; `None` returns every entry across
    * conversations. */
  def list(filterByConversation: Option[Id[Conversation]] = None): Task[List[ProcessHandle]] = Task {
    import scala.jdk.CollectionConverters.*
    entries.values().iterator().asScala.toList
      .filter(e => filterByConversation.forall(_ == e.handle.conversationId))
      .map(_.handle)
  }

  /** Diagnostic — current handle count. */
  def size: Int = entries.size()

  /** Best-effort: SIGTERM every live entry, then SIGKILL any that
    * don't exit within `terminateGraceMs`. Idempotent. Apps that
    * call this explicitly before JVM exit get clean teardown
    * ordering; the JVM shutdown hook covers crash-exits. */
  def terminateAll(): Unit = {
    val snapshot = {
      import scala.jdk.CollectionConverters.*
      entries.values().iterator().asScala.toList
    }
    snapshot.foreach { entry =>
      try entry.terminate(terminateGraceMs)
      catch { case _: Throwable => () }
    }
    entries.clear()
  }
}
