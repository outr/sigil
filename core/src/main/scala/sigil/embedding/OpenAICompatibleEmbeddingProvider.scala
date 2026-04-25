package sigil.embedding

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import rapid.Task
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.{ContentType, URL}

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

  private def postJson(url: URL, body: Json): Task[Json] =
    HttpClient.url(url)
      .header("Authorization", s"Bearer $apiKey")
      .post.content(StringContent(JsonFormatter.Compact(body), ContentType.`application/json`))
      .send()
      .flatMap { response =>
        response.content match {
          case Some(content) => content.asString.map(JsonParser(_))
          case None          => Task.error(new RuntimeException("Empty embedding response"))
        }
      }
}
