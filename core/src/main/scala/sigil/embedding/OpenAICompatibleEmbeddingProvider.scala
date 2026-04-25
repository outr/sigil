package sigil.embedding

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import rapid.Task
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.{ContentType, URL}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * [[EmbeddingProvider]] backed by the OpenAI-compatible embeddings
 * surface (`POST /v1/embeddings`). Works against OpenAI itself,
 * Ollama, Mistral, and any server implementing the same shape.
 *
 * `dimensions` must match the configured model — it's declared by the
 * caller so downstream `VectorIndex` initialization doesn't need to
 * probe with a throwaway call.
 */
case class OpenAICompatibleEmbeddingProvider(apiKey: String,
                                             baseUrl: URL,
                                             model: String,
                                             dimensions: Int) extends EmbeddingProvider {

  override def embed(text: String): Task[Vector[Double]] = {
    val body = obj("model" -> str(model), "input" -> str(text))
    postJson(baseUrl.withPath("/v1/embeddings"), body).map { json =>
      json("data").asVector.head("embedding").asVector.map(_.asDouble)
    }
  }

  override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] = {
    val body = obj("model" -> str(model), "input" -> arr(texts.map(str)*))
    postJson(baseUrl.withPath("/v1/embeddings"), body).map { json =>
      json("data").asVector.sortBy(_("index").asInt).map { item =>
        item("embedding").asVector.map(_.asDouble)
      }.toList
    }
  }

  /**
   * Send a JSON body and parse the response — with retry on transient
   * server errors. The OpenAI embeddings endpoint occasionally returns
   * 5xx (`{"error": {"message": "The server had an error..."}}`) under
   * load. A single failure used to abort an entire 25-minute benchmark
   * run because the parser tried `response("data")` on an error body
   * and threw. Retrying with backoff lets a one-shot hiccup resolve
   * itself before the call surfaces an error to callers.
   */
  private def postJson(url: URL, body: Json, attemptsLeft: Int = 3): Task[Json] =
    HttpClient.url(url)
      .header("Authorization", s"Bearer $apiKey")
      .noFailOnHttpStatus
      .post.content(StringContent(JsonFormatter.Compact(body), ContentType.`application/json`))
      .send()
      .flatMap { response =>
        val status = response.status.code
        val retriable = status >= 500 && status < 600
        if (retriable && attemptsLeft > 1) {
          Task.sleep(backoff(attemptsLeft))
            .flatMap(_ => postJson(url, body, attemptsLeft - 1))
        } else {
          response.content match {
            case Some(content) =>
              content.asString.flatMap { raw =>
                Task {
                  val parsed = JsonParser(raw)
                  // Some servers return 200 with `{"error": {...}}` on
                  // partial failures. Surface that as a Task error so
                  // the caller doesn't `.asVector.head` into a NoSuch.
                  if (status >= 400 || parsed.asObj.value.contains("error")) {
                    val msg = parsed.get("error").flatMap(_.get("message")).map(_.asString).getOrElse(raw)
                    throw new RuntimeException(s"Embedding request failed (HTTP $status): $msg")
                  }
                  parsed
                }
              }
            case None =>
              Task.error(new RuntimeException("Empty embedding response"))
          }
        }
      }

  /** Exponential backoff between retries: 200ms / 800ms / 2000ms for
    * the typical 3-attempt cycle. Keeps the worst-case retry window
    * under 3s so a benchmark loop doesn't stall noticeably. */
  private def backoff(attemptsLeft: Int): FiniteDuration = attemptsLeft match {
    case 3 => 200.millis
    case 2 => 800.millis
    case _ => 2.seconds
  }
}
