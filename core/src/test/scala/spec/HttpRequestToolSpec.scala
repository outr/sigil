package spec

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.tool.model.HttpRequestInput
import sigil.tool.web.HttpRequestTool

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

class HttpRequestToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  // Records the request bodies / headers / methods seen by the in-process
  // server so each test can assert what `http_request` actually sent.
  private case class CapturedRequest(method: String, path: String, headers: Map[String, String], body: String)
  private val captured = new AtomicReference[Option[CapturedRequest]](None)

  private val server: HttpServer = {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/echo", new HttpHandler {
      override def handle(ex: HttpExchange): Unit = {
        val body = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
        val hdrs = ex.getRequestHeaders.entrySet.iterator.asScala
          .map(e => e.getKey -> e.getValue.iterator.asScala.mkString(", "))
          .toMap
        captured.set(Some(CapturedRequest(
          method = ex.getRequestMethod, path = ex.getRequestURI.toString, headers = hdrs, body = body
        )))
        val responseBody = s"""{"echoed":${body.length},"method":"${ex.getRequestMethod}"}"""
        val bytes = responseBody.getBytes("UTF-8")
        ex.getResponseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length.toLong)
        val os = ex.getResponseBody; os.write(bytes); os.close()
      }
    })
    s.createContext("/notfound", new HttpHandler {
      override def handle(ex: HttpExchange): Unit = {
        val msg = "missing"
        val bytes = msg.getBytes("UTF-8")
        ex.getResponseHeaders.set("Content-Type", "text/plain")
        ex.sendResponseHeaders(404, bytes.length.toLong)
        val os = ex.getResponseBody; os.write(bytes); os.close()
      }
    })
    s.createContext("/large", new HttpHandler {
      override def handle(ex: HttpExchange): Unit = {
        // Emit 200 KB so the test can verify truncation behavior.
        val payload = "x" * 200_000
        val bytes = payload.getBytes("UTF-8")
        ex.getResponseHeaders.set("Content-Type", "text/plain")
        ex.sendResponseHeaders(200, bytes.length.toLong)
        val os = ex.getResponseBody; os.write(bytes); os.close()
      }
    })
    s.start()
    s
  }

  private val baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}"

  override protected def afterAll(): Unit = {
    server.stop(0)
    super.afterAll()
  }

  private val convId = Conversation.id("http-tool-spec-conv")
  private val ctx: TurnContext = TurnContext(
    sigil            = TestSigil,
    chain            = List(TestUser),
    conversation     = Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      _id    = convId
    ),
    turnInput        = TurnInput(ConversationView(conversationId = convId))
  )

  "HttpRequestTool" should {
    "issue a GET and return status / headers / body" in {
      captured.set(None)
      HttpRequestTool.invoke(HttpRequestInput(url = s"$baseUrl/echo"), ctx).map { out =>
        out.status shouldBe 200
        out.statusText should not be empty
        out.body should include(""""method":"GET"""")
        out.contentType.exists(_.toLowerCase.startsWith("application/json")) shouldBe true
        out.bodyTruncated shouldBe false
        captured.get().map(_.method) shouldBe Some("GET")
      }
    }

    "POST a body and forward request headers" in {
      captured.set(None)
      val body = """{"hello":"world"}"""
      HttpRequestTool.invoke(
        HttpRequestInput(
          url     = s"$baseUrl/echo",
          method  = "POST",
          headers = Map("X-Custom-Header" -> "marker-42"),
          body    = Some(body)
        ),
        ctx
      ).map { out =>
        out.status shouldBe 200
        out.body should include(s""""echoed":${body.length}""")
        val recorded = captured.get().getOrElse(fail("server did not see the request"))
        recorded.method shouldBe "POST"
        recorded.body shouldBe body
        recorded.headers.find { case (k, _) => k.equalsIgnoreCase("X-Custom-Header") }
          .map(_._2) shouldBe Some("marker-42")
      }
    }

    "surface non-2xx status without throwing" in {
      HttpRequestTool.invoke(HttpRequestInput(url = s"$baseUrl/notfound"), ctx).map { out =>
        out.status shouldBe 404
        out.body shouldBe "missing"
      }
    }

    "truncate response bodies past `maxResponseBytes` and flag the truncation" in {
      HttpRequestTool.invoke(
        HttpRequestInput(url = s"$baseUrl/large", maxResponseBytes = 4096),
        ctx
      ).map { out =>
        out.status shouldBe 200
        out.body.length shouldBe 4096
        out.bodyTruncated shouldBe true
      }
    }

    "fail loudly on an unsupported method" in {
      val attempt = HttpRequestTool.invoke(
        HttpRequestInput(url = s"$baseUrl/echo", method = "TELEPORT"),
        ctx
      ).attempt
      attempt.map(_.isFailure shouldBe true)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
