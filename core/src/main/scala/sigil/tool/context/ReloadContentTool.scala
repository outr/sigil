package sigil.tool.context

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.conversation.{ContextFrame, ContextSummary, Conversation, ToolCallState}
import sigil.event.{Event, Message}
import sigil.tool.ToolContext
import sigil.tool.model.ResponseContent
import sigil.tool.{DiscoverySpec, Effect, Freshness, TextToolOutput, Tool, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Reload content that context virtualization (#316) elided to save
 * budget, by id. When the curator trims a frame to a gist it leaves a
 * `[… reload_content("<id>")]` marker; the agent calls this with that id
 * to get the full content back:
 *
 *   - an `event id` — returns that event's full content.
 *   - a `summary id` — returns the list of events the summary covers
 *     (each with a snippet + its id); drill into one with
 *     `reload_content("<eventId>")`.
 *
 * The result lands directly in context; a large reload is written to a
 * file by the framework's overflow path (read it with `grep` /
 * `read_file`). Sigil #289 — accepts an optional `conversationId` to
 * read from a related conversation; cross-conversation reads are gated
 * by [[sigil.Sigil.canReadConversation]] (parent / worker only).
 */
case object ReloadContentTool extends Tool {
  type Input  = ReloadContentInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[ReloadContentInput]]
  val outputRW = summon[RW[TextToolOutput]]

  override val name = ToolName("reload_content")
  override val description =
    """Reload full content that was elided from your context to save budget.
      |
      |When an entry shows a `[… reload_content("<id>")]` marker, call this with that id:
      |  - an `event id` — returns that event's full content
      |  - a `summary id` — returns the list of events the summary covers (each with a
      |    snippet + its id); drill into one with `reload_content("<eventId>")`
      |
      |A large reload is written to a file you can read or search.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("reload", "expand", "content", "elided", "restore", "full", "rehydrate"))
  )

  override def executeResult(input: ReloadContentInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val targetConvId  = input.conversationId.getOrElse(ctx.conversation.id)
    val currentConvId = ctx.conversation.id
    ctx.sigil.canReadConversation(currentConvId, targetConvId).flatMap {
      case Left(reason) =>
        Task.pure(ToolResult.failure(
          message = s"reload_content: cannot read conversation `${targetConvId.value}` — $reason",
          hint = Some(
            "Cross-conversation reads are allowed only against the caller's own conversation, " +
              "its parent, or one of its workers."
          )
        ))
      case Right(_) =>
        resolveEventOrSummary(ctx, targetConvId, input.referenceId)
    }
  }

  /** Resolve a `referenceId` as a [[ContextSummary]] (browse its covered
    * events) or an [[Event]] (return its full content). Both stay scoped
    * to `convId`. */
  private def resolveEventOrSummary(ctx: ToolContext,
                                    convId: Id[Conversation],
                                    referenceId: String): Task[ToolResult[TextToolOutput]] =
    ctx.sigil.withDB(_.summaries.transaction(_.get(Id[ContextSummary](referenceId)))).flatMap {
      case Some(summary) if summary.conversationId == convId =>
        summaryText(ctx, summary)
      case _ =>
        ctx.sigil.withDB(_.eventsTransaction(convId)(_.get(Id[Event](referenceId)))).map {
          case Some(ev) if ev.conversationId == convId =>
            ToolResult.Success(TextToolOutput(eventContentText(ev)))
          case _ =>
            ToolResult.failure(s"reload_content: no event or summary `$referenceId` in this conversation.")
        }
    }

  /** A browsable index of a summary's covered events — `<eventId>: <snippet>`
    * per line; the agent drills into one via `reload_content("<eventId>")`. */
  private def summaryText(ctx: ToolContext, summary: ContextSummary): Task[ToolResult[TextToolOutput]] = {
    val ids = summary.coversEventIds
    if (ids.isEmpty) Task.pure(ToolResult.Success(TextToolOutput("(summary covers no events)")))
    else ctx.sigil.withDB(_.eventsTransaction(summary.conversationId) { tx =>
      Task.sequence(ids.map(tx.get))
    }).map { loaded =>
      val byId = loaded.flatten.map(e => e._id.value -> e).toMap
      val lines = ids.map { id =>
        val snippet = byId.get(id.value).map(e => eventContentText(e).take(160)).getOrElse("")
        s"${id.value}: $snippet"
      }
      ToolResult.Success(TextToolOutput(lines.mkString("\n")))
    }
  }

  /** The full rendered text an event carries — preferring its inlined
    * frame (which retains full content even when the per-turn render
    * elided it), falling back to a Message's text blocks. */
  private def eventContentText(ev: Event): String = ev.contextFrame match {
    case Some(t: ContextFrame.Text)      => t.content
    case Some(s: ContextFrame.System)    => s.content
    case Some(tc: ContextFrame.ToolCall) => tc.state match {
      case ToolCallState.Complete(c, _) => c
      case _                            => ""
    }
    case _ => ev match {
      case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString("\n")
      case _          => ""
    }
  }
}
