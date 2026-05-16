package sigil.tool.web

import fabric.{Arr, Json, num, obj, str}
import fabric.io.JsonFormatter
import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.{ResponseContent, WebSearchInput}
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Search the web via the configured [[SearchProvider]] (Tavily,
 * Brave, etc. — provider type is the app's choice). Result event
 * carries an array of `{title, url, snippet, score}`.
 */
final class WebSearchTool(provider: SearchProvider, defaultMaxResults: Int = 10)
  extends TypedTool[WebSearchInput](
    name = ToolName("web_search"),
    description =
      """Search the web for `query`. Returns up to `maxResults` results (default 10) — each carrying title,
        |URL, snippet, and (when the backend supplies one) a relevance score.""".stripMargin,
    examples = List(
      ToolExample("General lookup", WebSearchInput(query = "Scala 3 enums tutorial")),
      ToolExample("Top 5 only", WebSearchInput(query = "weather Tokyo today", maxResults = Some(5)))
    ),
    keywords = Set("web", "search", "google", "find", "lookup", "query", "internet")
  ) with sigil.tool.NetworkReadOnlyTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: WebSearchInput, ctx: TurnContext): Stream[Event] = Stream.force(
    provider.search(input.query, input.maxResults.getOrElse(defaultMaxResults)).map { results =>
      val items = results.toVector.map { r =>
        val base = Vector[(String, Json)](
          "title"   -> str(r.title),
          "url"     -> str(r.url),
          "snippet" -> str(r.snippet)
        )
        val withScore = r.score.fold(base)(s => base :+ ("score" -> num(s)))
        val withRaw   = r.rawContent.fold(withScore)(c => withScore :+ ("rawContent" -> str(c)))
        obj(withRaw*)
      }
      val payload = obj("results" -> Arr(items), "count" -> num(results.size))
      Stream.emit[Event](Message(
        participantId  = ctx.caller,
        conversationId = ctx.conversation.id,
        topicId        = ctx.conversation.currentTopicId,
        content        = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
        state          = EventState.Complete,
        role           = MessageRole.Tool
      ))
    }
  )
}
