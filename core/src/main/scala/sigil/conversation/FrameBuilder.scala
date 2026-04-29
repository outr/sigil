package sigil.conversation

import fabric.io.JsonFormatter
import fabric.rw.*
import fabric.{Json, Obj}
import sigil.event.{AgentState, Event, Message, ModeChange, MessageRole, Stop, TopicChange, TopicChangeKind, ToolInvoke, ToolResults}
import sigil.signal.EventState
import sigil.tool.ToolInput
import sigil.tool.ToolInput.given
import sigil.tool.model.ResponseContent

/**
 * Pure, provider-agnostic translation from a single [[Event]] (that has
 * reached `EventState.Complete`) into zero or more [[ContextFrame]]s for
 * a [[ConversationView]].
 *
 * Event → frame mapping rules (per-type, no runtime visibility flag):
 *   - `Message` → one `ContextFrame.Text`.
 *   - `ToolInvoke` → one `ContextFrame.ToolCall`. The `respond` tool is
 *     filtered at render time by the provider (the following `Message`
 *     is the response); the frame is still emitted here so the view
 *     carries the full history.
 *   - `ToolResults` → one `ContextFrame.ToolResult` whose `callId` points
 *     at the immediately-preceding pending `ToolInvoke`. If no pending
 *     call is found, falls back to a `System` frame so information isn't
 *     silently dropped.
 *   - `ModeChange` → one `ContextFrame.System`.
 *   - `TopicChange` → one `ContextFrame.System` (Switch vs. Rename
 *     reflected in the rendered text).
 *   - `AgentState`, `Stop`, and any other control-plane event →
 *     no frame. These are lifecycle / control signals the LLM shouldn't
 *     be re-reading as content.
 *
 * Active events (in-flight) are skipped — only Complete events become
 * frames.
 */
object FrameBuilder {

  /**
   * Append frames for a newly-Complete event to an existing frame vector.
   * The return value is the updated vector.
   *
   * `existing` is the vector the new frames are appended to; it's supplied
   * so [[ToolResults]] can pair against the most-recent pending ToolCall.
   */
  def appendFor(existing: Vector[ContextFrame], event: Event): Vector[ContextFrame] = {
    if (event.state != EventState.Complete) return existing

    // Tool-result rendering takes precedence over the per-subclass match
    // — any event whose role is MessageRole.Tool is paired against the
    // most-recent unresolved ToolInvoke and rendered to JSON via
    // [[stripEventBoilerplate]] (framework metadata fields removed so
    // the model sees only the typed payload).
    if (event.role == MessageRole.Tool) {
      // Tool-result rendering: for typed Events (ChangeMode, ToolResults,
      // etc.) strip framework boilerplate and render the JSON of the
      // remaining typed fields. For `Message`, the typed fields are the
      // wrapper itself (`content: Vector[ResponseContent]` etc.) — the
      // agent wants just the text the tool wrote, not the Message
      // record. Extract the text directly so models see a clean tool
      // result instead of doubly-wrapped JSON.
      val content = event match {
        case m: Message =>
          m.content.collect { case ResponseContent.Text(t) => t }.mkString("\n")
        case other =>
          val payload = stripEventBoilerplate(Event.rw.read(other))
          JsonFormatter.Compact(payload)
      }
      return pairedCallId(existing) match {
        case Some(callId) =>
          existing :+ ContextFrame.ToolResult(
            callId = callId,
            content = content,
            sourceEventId = event._id,
            visibility = event.visibility
          )
        case None =>
          existing :+ ContextFrame.System(
            content = s"Tool result (orphan): $content",
            sourceEventId = event._id,
            visibility = event.visibility
          )
      }
    }

    event match {
      case m: Message =>
        existing :+ ContextFrame.Text(
          content = renderMessageText(m),
          participantId = m.participantId,
          sourceEventId = m._id,
          visibility = m.visibility
        )

      case ti: ToolInvoke =>
        val argsJson = ti.input
          .map(i => JsonFormatter.Compact(stripPolyDiscriminator(summon[RW[ToolInput]].read(i))))
          .getOrElse("{}")
        existing :+ ContextFrame.ToolCall(
          toolName = ti.toolName,   // already ToolName
          argsJson = argsJson,
          callId = ti._id,
          participantId = ti.participantId,
          sourceEventId = ti._id,
          visibility = ti.visibility
        )

      case mc: ModeChange =>
        existing :+ ContextFrame.System(
          content = s"Mode changed to ${mc.mode}${mc.reason.map(r => s" ($r)").getOrElse("")}.",
          sourceEventId = mc._id,
          visibility = mc.visibility
        )

      case tc: TopicChange =>
        val content = tc.kind match {
          case TopicChangeKind.Switch(_)            => s"Topic switched to: ${tc.newLabel}"
          case TopicChangeKind.Rename(previousLabel) => s"Topic renamed: $previousLabel → ${tc.newLabel}"
        }
        existing :+ ContextFrame.System(
          content = content,
          sourceEventId = tc._id,
          visibility = tc.visibility
        )

      case _: AgentState | _: Stop =>
        existing

      case other =>
        // Unknown Event type — fail loud so gaps are caught. Adding a new
        // Event requires making a deliberate decision here: emit a frame,
        // or mark it as a control-plane event that stays out of the view.
        throw new RuntimeException(
          s"FrameBuilder: Event ${other.getClass.getSimpleName} has no frame rule. " +
            s"Add a case here to either emit a frame or mark it control-plane (no frame)."
        )
    }
  }

  /**
   * Fold a chronologically-sorted sequence of events into a fresh frame
   * vector. Used by `Sigil.rebuildView` for recovery / migration.
   */
  def build(events: Iterable[Event]): Vector[ContextFrame] =
    events.foldLeft(Vector.empty[ContextFrame])((acc, e) => appendFor(acc, e))

  /**
   * Apply the participant-projection deltas this event implies:
   *   - `ToolInvoke` complete → push toolName onto `recentTools`
   *   - `ToolResults` complete → replace `suggestedTools` with fresh
   *     matches
   *
   * `activeSkills` are driven by app-supplied events; the framework
   * doesn't materialize them here (apps can override via custom
   * publish-path hooks).
   */
  def updateProjections(existing: Map[sigil.participant.ParticipantId, ParticipantProjection],
                        event: Event): Map[sigil.participant.ParticipantId, ParticipantProjection] = {
    if (event.state != EventState.Complete) return existing
    event match {
      case ti: ToolInvoke =>
        val pid = ti.participantId
        val proj = existing.getOrElse(pid, ParticipantProjection())
        val updated = proj.copy(recentTools = ti.toolName :: proj.recentTools.filterNot(_ == ti.toolName))
        existing + (pid -> updated)

      case tr: ToolResults =>
        val pid = tr.participantId
        val proj = existing.getOrElse(pid, ParticipantProjection())
        val updated = proj.copy(suggestedTools = tr.schemas.map(_.name).toList)
        existing + (pid -> updated)

      case _ => existing
    }
  }

  private def renderMessageText(m: Message): String =
    m.content
      .map {
        case ResponseContent.Text(t) => t
        case ResponseContent.Markdown(t) => t
        case ResponseContent.Code(c, lang) => s"```${lang.getOrElse("")}\n$c\n```"
        case other => other.toString
      }
      .mkString("\n")

  /**
   * Strip the `ToolInput` poly discriminator so the `arguments` JSON
   * is pure parameter-schema on the wire. Same logic the llama.cpp
   * provider used to do at render time; moved here so frames already
   * carry the clean form.
   */
  private def stripPolyDiscriminator(json: Json): Json = json match {
    case o: Obj => Obj(o.value - "type")
    case other => other
  }

  /**
   * Strip the framework's standard Event fields (`_id`,
   * `participantId`, `conversationId`, `topicId`, `state`,
   * `timestamp`, `role`) plus the polymorphic Signal discriminator
   * (`type`) from a JSON-rendered Event. What remains is the
   * event-specific typed payload — what the model should see when
   * the event is rendered as a tool result.
   *
   * Example: `BalanceRead(balance = 1810.0, ...)` rendered via
   * `Event.rw.read` gives `{"type": "BalanceRead", "balance":
   * 1810.0, "participantId": "...", ...}`. After this strip:
   * `{"balance": 1810.0}` — clean payload only.
   */
  private val EventBoilerplateFields: Set[String] =
    Set("type", "_id", "participantId", "conversationId", "topicId", "state", "timestamp", "role")

  private def stripEventBoilerplate(json: Json): Json = json match {
    case o: Obj => Obj(o.value -- EventBoilerplateFields)
    case other => other
  }

  /**
   * Find the most-recently-added `ToolCall` frame that hasn't yet been
   * paired with a `ToolResult`. A pending call is one that has no
   * corresponding `ToolResult(callId = _)` later in the vector.
   */
  private def pairedCallId(frames: Vector[ContextFrame]): Option[lightdb.id.Id[Event]] = {
    val resolved = frames.collect { case ContextFrame.ToolResult(id, _, _, _) => id }.toSet
    frames.reverseIterator.collectFirst {
      case ContextFrame.ToolCall(_, _, callId, _, _, _) if !resolved.contains(callId) => callId
    }
  }
}
