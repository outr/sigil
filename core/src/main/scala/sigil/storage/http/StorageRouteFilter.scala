package sigil.storage.http

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.participant.ParticipantId
import sigil.storage.StoredFile
import spice.http.server.handler.HttpHandler
import spice.http.server.MutableHttpServer
import spice.http.{HttpExchange, HttpStatus}
import spice.http.content.Content
import spice.http.{Headers, HttpResponse}
import spice.net.ContentType

/**
 * Helper that wires Sigil's storage route into an existing
 * [[MutableHttpServer]]. Calling [[mount]] registers an HTTP
 * handler that resolves `/storage/<id>` requests back through
 * [[Sigil.fetchStoredFile]] and streams the bytes to the client.
 *
 * Usage:
 *
 * {{{
 *   val server = new MutableHttpServer
 *   StorageRouteFilter.mount(server, sigil, authn)
 *   // ... register other handlers ...
 *   server.start()
 * }}}
 *
 * `authn` resolves the requester's chain from cookies / auth header /
 * session — the route applies `Sigil.accessibleSpaces(chain)` before
 * serving so file access honors the framework's space scoping. Apps
 * with no auth concept pass [[OpenAuth]] (returns `Nil`) and override
 * [[Sigil.accessibleSpaces]] to allow open access.
 *
 * S3-backed bytes are streamed through this route — the backend's
 * native URL is never exposed to the client. Switching backends
 * (local FS → S3 → CDN-fronted S3) doesn't change the URL the UI
 * sees.
 */
object StorageRouteFilter {

  def mount(server: MutableHttpServer,
            sigil: Sigil,
            authn: HttpExchange => Task[List[ParticipantId]] = OpenAuth,
            pathPrefix: String = "/storage/"): Unit = {
    server.handler.handle { exchange =>
      val path = exchange.request.url.path.decoded
      if (!path.startsWith(pathPrefix)) {
        Task.pure(exchange)
      } else {
        val idStr = path.stripPrefix(pathPrefix)
        if (idStr.isEmpty || idStr.contains('/')) Task.pure(exchange)
        else handle(exchange, sigil, authn, Id(idStr)).handleError { throwable =>
          scribe.error(s"StorageRouteFilter error for $path: ${throwable.getMessage}", throwable)
          send404(exchange)
        }
      }
    }
    ()
  }

  private def handle(exchange: HttpExchange,
                     sigil: Sigil,
                     authn: HttpExchange => Task[List[ParticipantId]],
                     id: Id[StoredFile]): Task[HttpExchange] =
    authn(exchange).flatMap { chain =>
      sigil.fetchStoredFile(id, chain).flatMap {
        case None => send404(exchange)
        case Some((file, bytes)) =>
          val ct = ContentType.parse(file.contentType)
          val content = Content.bytes(bytes, ct)
          exchange.modify { response =>
            Task(response.withContent(content).withHeader(Headers.`Content-Length`(content.length)))
          }
      }
    }

  private def send404(exchange: HttpExchange): Task[HttpExchange] = {
    val content = Content.string("Not Found", ContentType.`text/plain`)
    exchange.modify { response =>
      Task(response
        .withStatus(HttpStatus.NotFound)
        .withContent(content)
        .withHeader(Headers.`Content-Length`(content.length)))
    }
  }

  /** No-op authn — every request gets `Nil` chain. Combined with an
    * `accessibleSpaces` override that returns the universe, this
    * gives unauthenticated public access. Only safe for trusted
    * networks / dev. */
  val OpenAuth: HttpExchange => Task[List[ParticipantId]] = _ => Task.pure(Nil)
}
