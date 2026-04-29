package sigil.workflow.trigger

import fabric.{Json, Null, obj, str}
import rapid.Task
import scribe.mdc.MDC
import spice.http.content.{Content, StringContent}
import spice.http.server.handler.HttpHandler
import spice.http.{HttpExchange, HttpMethod, HttpStatus}

/**
 * spice [[HttpHandler]] that bridges inbound HTTP webhooks to the
 * matching [[WebhookTrigger]] queue. Apps mount this on their HTTP
 * server's path tree at the chosen base — typically `/webhooks` —
 * and inbound `POST /<base>/<triggerPath>` requests deliver the
 * JSON body to the trigger.
 *
 * Validates `X-Webhook-Secret` against the registered trigger's
 * configured secret. Mismatches respond `403 Forbidden`. Unknown
 * paths (no trigger registered) respond `404 Not Found`. Successful
 * deliveries respond `202 Accepted`.
 *
 * The handler matches every request whose URL path starts with
 * `basePath`. Apps that want a different prefix construct multiple
 * handlers — mount points are app-policy.
 *
 * Usage (Sage / app-side):
 *
 * {{{
 *   val webhook = new WorkflowWebhookHandler(basePath = "/webhooks", secrets = Map("build" -> "topsecret"))
 *   httpServer.handler(webhook)
 * }}}
 *
 * The `secrets` map is keyed by the trigger path (the URL segment
 * after `basePath`). Apps populate this from their persisted
 * `WebhookTrigger` configurations — typically by walking the
 * registered templates' `triggers` lists at server startup.
 */
final class WorkflowWebhookHandler(basePath: String,
                                   secrets: Map[String, String]) extends HttpHandler {
  private val SecretHeader = "X-Webhook-Secret"
  private val normalizedBase: String = if (basePath.endsWith("/")) basePath.dropRight(1) else basePath

  override def handle(exchange: HttpExchange)(using mdc: MDC): Task[HttpExchange] = {
    val path = exchange.request.url.path.encoded
    if (!path.startsWith(s"$normalizedBase/")) Task.pure(exchange)
    else if (exchange.request.method != HttpMethod.Post) reply(exchange, HttpStatus.MethodNotAllowed, "POST only")
    else {
      val triggerPath = path.substring(normalizedBase.length + 1)
      secrets.get(triggerPath) match {
        case None =>
          reply(exchange, HttpStatus.NotFound, s"No webhook trigger registered at /$triggerPath")
        case Some(expected) =>
          val received = exchange.request.headers.first(spice.http.StringHeaderKey(SecretHeader)).getOrElse("")
          if (received != expected)
            reply(exchange, HttpStatus.Forbidden, "Invalid webhook secret")
          else exchange.request.content match {
            case None =>
              WebhookTrigger.queueFor(triggerPath).add(obj("body" -> str("")))
              reply(exchange, HttpStatus.Accepted, "delivered")
            case Some(content) =>
              content.asString.flatMap { bodyStr =>
                val payload: Json = scala.util.Try(fabric.io.JsonParser(bodyStr))
                  .getOrElse(obj("body" -> str(bodyStr)))
                WebhookTrigger.queueFor(triggerPath).add(payload)
                reply(exchange, HttpStatus.Accepted, "delivered")
              }
          }
      }
    }
  }

  private def reply(exchange: HttpExchange, status: HttpStatus, body: String): Task[HttpExchange] = {
    exchange.modify { response =>
      Task.pure(response.withStatus(status).withContent(StringContent(body, spice.net.ContentType.`text/plain`)))
    }.map(_.finish())
  }
}
