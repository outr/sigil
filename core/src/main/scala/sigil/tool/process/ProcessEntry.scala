package sigil.tool.process

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets

/**
 * Internal record kept by [[ProcessRegistry]] for each registered
 * subprocess. Owns the JVM `Process`, the per-stream ring buffers,
 * and the daemon threads that drain stdout / stderr.
 */
final private[process] class ProcessEntry(val handle: ProcessHandle,
                                          val process: Process,
                                          val stdout: RingBuffer,
                                          val stderr: RingBuffer,
                                          stdinText: Option[String]) {

  private val drainOut = new Thread(() => drain(process.getInputStream, stdout), s"sigil-proc-${handle.id}-stdout")
  private val drainErr = new Thread(() => drain(process.getErrorStream, stderr), s"sigil-proc-${handle.id}-stderr")
  drainOut.setDaemon(true); drainErr.setDaemon(true)
  drainOut.start(); drainErr.start()

  // Pipe the supplied stdin payload (if any) and close the stream so
  // the child sees EOF — interactive servers that read stdin to hold
  // open get the data plus the EOF cue immediately.
  stdinText.foreach { text =>
    val w = new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8)
    try { w.write(text); w.flush() }
    finally w.close()
  }

  def status: ProcessStatus =
    if (process.isAlive) ProcessStatus.Running
    else ProcessStatus.Exited(process.exitValue())

  def exitCode: Option[Int] = if (process.isAlive) None else Some(process.exitValue())

  def terminate(graceMs: Long): Unit = {
    if (!process.isAlive) return
    process.destroy() // SIGTERM
    val exited = process.waitFor(graceMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    if (!exited) process.destroyForcibly() // SIGKILL
  }

  def kill(): Unit = if (process.isAlive) process.destroyForcibly()

  private def drain(in: java.io.InputStream, target: RingBuffer): Unit = {
    val reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
    try {
      var line = reader.readLine()
      while (line != null) {
        target.append(line + "\n")
        line = reader.readLine()
      }
    } catch {
      case _: java.io.IOException => () // stream closed during shutdown
    } finally reader.close()
  }
}

private[process] object ProcessEntry {

  def spawn(command: String,
            workingDir: Option[String],
            env: Map[String, String],
            stdin: Option[String],
            handleId: String,
            conversationId: Id[Conversation],
            ringBytes: Int): ProcessEntry = {
    val pb = new ProcessBuilder("bash", "-c", command)
    workingDir.foreach(d => pb.directory(new java.io.File(d)))
    val envMap = pb.environment()
    env.foreach { case (k, v) => envMap.put(k, v) }
    pb.redirectErrorStream(false)
    val process = pb.start()
    val handle = ProcessHandle(
      id = handleId,
      pid = process.pid(),
      startedAt = Timestamp(),
      conversationId = conversationId,
      command = command
    )
    new ProcessEntry(handle, process, new RingBuffer(ringBytes), new RingBuffer(ringBytes), stdin)
  }
}
