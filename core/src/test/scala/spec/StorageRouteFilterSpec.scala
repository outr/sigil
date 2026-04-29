package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.storage.http.StorageRouteFilter
import spice.http.{HttpExchange, HttpRequest, HttpStatus}
import spice.http.content.BytesContent
import spice.http.server.MutableHttpServer
import spice.net.url

import scala.language.implicitConversions

/**
 * Coverage for [[StorageRouteFilter]]: register the route on a
 * `MutableHttpServer` and exercise it via in-process exchanges
 * (`server.handle(HttpExchange(HttpRequest(...)))`) — no real
 * HTTP listener, no client. Mirrors the pattern used by spice's
 * own `ServerSpec`.
 *
 * The route must:
 *   - serve bytes for an authorized file id
 *   - return 404 for missing file ids
 *   - return 404 (not 200) when the caller's chain isn't authorized
 *     for the file's `SpaceId`
 *   - never expose the storage backend's native URL — the file is
 *     fetched through `Sigil.fetchStoredFile`
 */
class StorageRouteFilterSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Open authz: every fetch sees GlobalSpace + TestSpace, so the route
  // serves any file we write under those spaces.
  TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace, TestSpace)))

  private val server: MutableHttpServer = {
    val s = new MutableHttpServer
    StorageRouteFilter.mount(s, TestSigil, StorageRouteFilter.OpenAuth)
    s
  }

  private def get(path: String): Task[HttpExchange] =
    server.handle(HttpExchange(HttpRequest(url = url"http://localhost".withPath(path))))

  "StorageRouteFilter" should {

    "serve bytes for an authorized file by id" in {
      val payload = "route-payload".getBytes("UTF-8")
      for {
        stored   <- TestSigil.storeBytes(GlobalSpace, payload, "text/plain")
        exchange <- get(s"/storage/${stored._id.value}")
      } yield {
        exchange.response.status shouldBe HttpStatus.OK
        val content = exchange.response.content
        content shouldBe defined
        new String(content.get.asInstanceOf[BytesContent].value, "UTF-8") shouldBe "route-payload"
      }
    }

    "return 404 for a missing file id" in {
      for {
        exchange <- get("/storage/this-id-does-not-exist")
      } yield {
        exchange.response.status shouldBe HttpStatus.NotFound
      }
    }

    "return 404 when the caller is not authorized for the file's space" in {
      val payload = "guarded".getBytes("UTF-8")
      for {
        stored   <- TestSigil.storeBytes(TestSpace, payload, "text/plain")
        // Strip auth: OpenAuth callers see no spaces.
        _        <- Task { TestSigil.setAccessibleSpaces(_ => Task.pure(Set.empty)) }
        exchange <- get(s"/storage/${stored._id.value}")
        // Re-grant for any subsequent tests in this suite.
        _        <- Task { TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace, TestSpace))) }
      } yield {
        exchange.response.status shouldBe HttpStatus.NotFound
      }
    }

    "fall through (404 / no content from this filter) for non-/storage paths" in {
      for {
        exchange <- get("/somewhere-else")
      } yield {
        // The framework's default NotFound handler fires when no handler
        // claims the request. The storage route returns the exchange
        // untouched for non-/storage paths.
        exchange.response.status shouldBe HttpStatus.NotFound
      }
    }
  }
}
