package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.llamacpp.LlamaCppTokenizer
import sigil.tokenize.HeuristicTokenizer
import spice.net.URL

import java.io.IOException
import java.net.{InetSocketAddress, ServerSocket}
import scala.concurrent.duration.*

/**
 * Direct verification that `LlamaCppTokenizer.requestTimeout` is
 * actually honored end-to-end against a misbehaving server.
 */
class LlamaCppHangIsolationSpec extends AnyWordSpec with Matchers {

  /** Spawn a TCP server that accepts then sleeps. Holds the
    * connection open without responding. Used to force a server-side
    * read-timeout. Returns the port + a `close()` thunk. */
  private def startHangingServer(): (Int, () => Unit) = {
    val server = new ServerSocket()
    server.setReuseAddress(true)
    server.bind(new InetSocketAddress("127.0.0.1", 0))
    val port = server.getLocalPort
    @volatile var running = true
    val thread = new Thread(() => {
      try while (running) {
        val socket = server.accept()
        // Hold the socket — read input but never write a response.
        new Thread(() => {
          try {
            val in = socket.getInputStream
            // Drain headers slowly; never write back.
            val buf = new Array[Byte](4096)
            while (running && socket.isConnected && !socket.isClosed) {
              val r = in.read(buf)
              if (r <= 0) Thread.sleep(50)
            }
          } catch {
            case _: IOException => // socket closed, fine
          }
        }, s"hang-handler-$port").start()
      } catch {
        case _: IOException => // server closed
      }
    }, s"hang-server-$port")
    thread.setDaemon(true)
    thread.start()
    (port, () => { running = false; try server.close() catch { case _: IOException => } })
  }

  "LlamaCppTokenizer requestTimeout" should {
    "fail within a bounded wall-clock window when the server hangs (not 60s)" in {
      val (port, close) = startHangingServer()
      try {
        val url = URL.parse(s"http://127.0.0.1:$port")
        val tok = LlamaCppTokenizer(
          baseUrl = url,
          fallback = HeuristicTokenizer,
          requestTimeout = 2.seconds,
          cacheSize = 64,
          breakerThreshold = 1000
        )
        val start = System.currentTimeMillis()
        val n = tok.count("verify-bounded-wait")
        val elapsed = System.currentTimeMillis() - start
        // Worst case: 2 attempts × 2s timeout + 1 × 100ms retry = 4.1s
        // Plus a small slack for thread scheduling / cleanup.
        // Critically, NOT 60s. If we ever cross 10s here,
        // the timeout isn't taking effect.
        elapsed should be < 10000L
        n shouldBe HeuristicTokenizer.count("verify-bounded-wait")
        info(s"hang call returned in ${elapsed}ms (heuristic fallback fired)")
      } finally close()
    }
  }
}
