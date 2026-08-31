package spec

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spice.net.*

import java.net.InetSocketAddress

/**
 * The `SIGIL_LLAMACPP_HOST` override is only usable when a llama.cpp
 * server is actually behind it. A bare TCP probe can't tell that apart
 * from an unrelated service squatting on the port — that host accepts
 * the socket, passes the probe, and then 404s every `/v1` call, so
 * live suites fail on raw HTTP 404s instead of degrading to the public
 * endpoint.
 *
 * Pinned here: the probe asks for `/v1/models` and only a 2xx selects
 * the configured host. No real endpoint is contacted — the fallback is
 * a plain URL value the probe never calls.
 */
class LlamaCppHostProbeSpec extends AnyWordSpec with Matchers {

  private val fallback: URL = url"https://llama.voidcraft.invalid"

  /**
   * Run `f` against a locally-bound stub server, always stopping it after.
   */
  private def withStub(handler: HttpHandler)(f: URL => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress, 0), 0)
    server.createContext("/", handler)
    server.start()
    try f(url"http://localhost".withPort(server.getAddress.getPort))
    finally server.stop(0)
  }

  private def send(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes("UTF-8")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val out = exchange.getResponseBody
    try out.write(bytes)
    finally out.close()
  }

  /**
   * Answers `/v1/models` like llama.cpp does; 404s anything else.
   */
  private val llamaCppLike: HttpHandler = (exchange: HttpExchange) =>
    if (exchange.getRequestURI.getPath == "/v1/models")
      send(exchange, 200, """{"object":"list","data":[{"id":"local-model"}]}""")
    else send(exchange, 404, "not found")

  /**
   * Accepts the socket, then 404s everything — the squatter case.
   */
  private val squatter: HttpHandler = (exchange: HttpExchange) => send(exchange, 404, "not found")

  "TestSigil.hostHealthy" should {
    "reject a host that accepts TCP but does not serve /v1/models" in
      withStub(squatter)(stub => TestSigil.hostHealthy(stub) shouldBe false)

    "accept a host that serves /v1/models" in
      withStub(llamaCppLike)(stub => TestSigil.hostHealthy(stub) shouldBe true)

    "reject a host with nothing listening" in {
      TestSigil.hostHealthy(url"http://localhost".withPort(closedPort)) shouldBe false
    }
  }

  "TestSigil.resolveLlamaCppHost" should {
    "fall back to the public endpoint when the configured host only accepts the socket" in
      withStub(squatter)(stub => TestSigil.resolveLlamaCppHost(Some(stub), fallback) shouldBe fallback)

    "select the configured host when it serves /v1/models" in
      withStub(llamaCppLike)(stub => TestSigil.resolveLlamaCppHost(Some(stub), fallback) shouldBe stub)

    "fall back when no host is configured" in {
      TestSigil.resolveLlamaCppHost(None, fallback) shouldBe fallback
    }
  }

  /**
   * A port that was bound and released, so nothing is listening on it.
   */
  private def closedPort: Int = {
    val socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress)
    try socket.getLocalPort
    finally socket.close()
  }
}
